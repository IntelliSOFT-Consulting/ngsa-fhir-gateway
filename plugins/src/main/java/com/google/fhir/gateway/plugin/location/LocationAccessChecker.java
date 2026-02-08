/*
 * Copyright 2021-2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.gateway.plugin.location;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.base.Preconditions;
import com.google.fhir.gateway.FhirUtil;
import com.google.fhir.gateway.HttpFhirClient;
import com.google.fhir.gateway.HttpUtil;
import com.google.fhir.gateway.JwtUtil;
import com.google.fhir.gateway.interfaces.AccessChecker;
import com.google.fhir.gateway.interfaces.AccessDecision;
import com.google.fhir.gateway.interfaces.NoOpAccessDecision;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import com.google.fhir.gateway.interfaces.RequestMutation;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.http.HttpResponse;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Location-aware access checker.
 *
 * <p>Enforcement:
 *
 * <ul>
 *   <li>GET searches are rewritten by adding a required {@code _tag} filter
 *   <li>Reads/updates/deletes validate resource tags against the user's assigned location
 *   <li>Writes persist tags post-write using backend JSON Patch
 *   <li>Successful Location mutations update the Postgres location cache
 * </ul>
 */
public final class LocationAccessChecker implements AccessChecker {

  private static final Logger logger = LoggerFactory.getLogger(LocationAccessChecker.class);

  private final DecodedJWT jwt;
  private final HttpFhirClient httpFhirClient;
  private final FhirContext fhirContext;
  private final LocationAccessConfig config;
  private final LocationCachingService cache;

  private final String practitionerId;
  private final String userRole;
  private final String userAssignedLocationId;
  private final String userAccessLevelTypeCode;
  private final LocationTagMutator tagMutator;

  public LocationAccessChecker(
      DecodedJWT jwt,
      HttpFhirClient httpFhirClient,
      FhirContext fhirContext,
      LocationAccessConfig config,
      LocationCachingService cache) {
    this.jwt = Preconditions.checkNotNull(jwt, "jwt");
    this.httpFhirClient = Preconditions.checkNotNull(httpFhirClient, "httpFhirClient");
    this.fhirContext = Preconditions.checkNotNull(fhirContext, "fhirContext");
    this.config = Preconditions.checkNotNull(config, "config");
    this.cache = Preconditions.checkNotNull(cache, "cache");

    this.practitionerId = extractPractitionerId(jwt, config);

    try {
      LocationCachingService.PractitionerContext ctx =
          cache.getPractitionerContext(practitionerId, config, httpFhirClient, fhirContext);
      this.userRole = ctx.role();
      this.userAssignedLocationId = ctx.primaryLocationId();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to resolve practitioner context", e);
    }

    this.userAccessLevelTypeCode = config.accessLevelTypeCodeForRole(userRole);
    this.tagMutator = new LocationTagMutator(config, cache);

    logger.info(
        "Initialized LocationAccessChecker for practitioner {} role {} accessLevel {}"
            + " assignedLocation {}",
        practitionerId,
        userRole,
        userAccessLevelTypeCode,
        userAssignedLocationId);
  }

  @Override
  public AccessDecision checkAccess(RequestDetailsReader requestDetails) {
    try {
      // Bundle transactions come as POST with null resourceName.
      if (requestDetails.getRequestType() == RequestTypeEnum.POST
          && requestDetails.getResourceName() == null) {
        return processBundle(requestDetails);
      }

      // Special-case Location: allow COUNTRY-level only, and update cache on mutations.
      if ("Location".equals(requestDetails.getResourceName())) {
        return processLocationResource(requestDetails);
      }

      switch (requestDetails.getRequestType()) {
        case GET:
          return processGet(requestDetails);
        case POST:
          return processCreate(requestDetails);
        case PUT:
          return processUpdate(requestDetails);
        case PATCH:
          return processPatch(requestDetails);
        case DELETE:
          return processDelete(requestDetails);
        default:
          return NoOpAccessDecision.accessDenied();
      }
    } catch (IOException e) {
      logger.error("Error checking access; denying.", e);
      return NoOpAccessDecision.accessDenied();
    }
  }

  private AccessDecision processLocationResource(RequestDetailsReader requestDetails)
      throws IOException {
    if (!config.getRootLocationTypeCode().equals(userAccessLevelTypeCode)) {
      return NoOpAccessDecision.accessDenied();
    }

    // For Location mutations, allow and update cache in postProcess.
    if (requestDetails.getRequestType() == RequestTypeEnum.POST
        || requestDetails.getRequestType() == RequestTypeEnum.PUT
        || requestDetails.getRequestType() == RequestTypeEnum.PATCH
        || requestDetails.getRequestType() == RequestTypeEnum.DELETE) {
      return LocationTaggingAccessDecision.persistTagsForWrite(
          fhirContext,
          httpFhirClient,
          cache,
          config.getLocationTagSystem(),
          null,
          userAccessLevelTypeCode,
          userAssignedLocationId,
          config.getLeafLocationTypeCode(),
          ResourceType.Location,
          true);
    }

    // For Location reads/searches at COUNTRY level, allow (no tag enforcement).
    return LocationTaggingAccessDecision.allow();
  }

  private AccessDecision processGet(RequestDetailsReader requestDetails) throws IOException {
    if (requestDetails.getId() == null) {
      return processSearch(requestDetails);
    }
    return processRead(requestDetails);
  }

  private AccessDecision processSearch(RequestDetailsReader requestDetails) {
    if (config.getRootLocationTypeCode().equals(userAccessLevelTypeCode)) {
      return LocationTaggingAccessDecision.allow();
    }

    String leafType = config.getLeafLocationTypeCode();
    List<String> leafLocationIds;
    if (leafType.equals(userAccessLevelTypeCode)) {
      leafLocationIds = List.of(userAssignedLocationId);
    } else {
      int limit = config.getMaxDescendantTagsInSearch();
      leafLocationIds = cache.getDescendantIdsByType(userAssignedLocationId, leafType, limit);
      if (leafLocationIds.size() > limit) {
        logger.warn(
            "Too many {} descendants under {}; denying search to fail closed.",
            leafType,
            userAssignedLocationId);
        return NoOpAccessDecision.accessDenied();
      }
      if (leafLocationIds.isEmpty()) {
        logger.warn(
            "No cached {} descendants under {}; denying search to fail closed.",
            leafType,
            userAssignedLocationId);
        return NoOpAccessDecision.accessDenied();
      }
    }

    List<String> tokenValues =
        leafLocationIds.stream()
            .map(id -> config.getLocationTagSystem() + "|Location/" + id)
            .collect(java.util.stream.Collectors.toList());

    RequestMutation mutation =
        RequestMutation.builder()
            .discardQueryParams(List.of("_tag"))
            .additionalQueryParams(Map.of("_tag", tokenValues))
            .build();
    return LocationTaggingAccessDecision.withSearchMutation(mutation);
  }

  private AccessDecision processRead(RequestDetailsReader requestDetails) throws IOException {
    String id = FhirUtil.getIdOrNull(requestDetails);
    if (id == null) {
      return NoOpAccessDecision.accessDenied();
    }

    String path = requestDetails.getResourceName() + "/" + id;
    HttpResponse response = httpFhirClient.getResource(path);
    if (response.getStatusLine().getStatusCode() == 404) {
      return NoOpAccessDecision.accessDenied();
    }
    HttpUtil.validateResponseEntityOrFail(response, path);

    Resource resource =
        (Resource) fhirContext.newJsonParser().parseResource(response.getEntity().getContent());

    boolean ok =
        tagMutator.isAccessibleByTags(
            resource, userAssignedLocationId, userAccessLevelTypeCode, httpFhirClient, fhirContext);
    return new NoOpAccessDecision(ok);
  }

  private AccessDecision processCreate(RequestDetailsReader requestDetails) throws IOException {
    Resource resource = (Resource) FhirUtil.createResourceFromRequest(fhirContext, requestDetails);

    // Validate we can determine facility tag (required for non-facility users).
    boolean ok =
        !tagMutator
            .computeTagsForWrite(
                resource,
                userAssignedLocationId,
                userAccessLevelTypeCode,
                httpFhirClient,
                fhirContext)
            .isEmpty();
    if (!ok) {
      return NoOpAccessDecision.accessDenied();
    }

    return LocationTaggingAccessDecision.persistTagsForWrite(
        fhirContext,
        httpFhirClient,
        cache,
        config.getLocationTagSystem(),
        tagMutator,
        userAccessLevelTypeCode,
        userAssignedLocationId,
        config.getLeafLocationTypeCode(),
        ResourceType.fromCode(requestDetails.getResourceName()),
        false);
  }

  private AccessDecision processUpdate(RequestDetailsReader requestDetails) throws IOException {
    // Validate access to existing resource (if ID provided).
    if (requestDetails.getId() != null) {
      AccessDecision canRead = processRead(requestDetails);
      if (!canRead.canAccess()) {
        return NoOpAccessDecision.accessDenied();
      }
    }

    Resource resource = (Resource) FhirUtil.createResourceFromRequest(fhirContext, requestDetails);
    boolean ok =
        !tagMutator
            .computeTagsForWrite(
                resource,
                userAssignedLocationId,
                userAccessLevelTypeCode,
                httpFhirClient,
                fhirContext)
            .isEmpty();
    if (!ok) {
      return NoOpAccessDecision.accessDenied();
    }

    return LocationTaggingAccessDecision.persistTagsForWrite(
        fhirContext,
        httpFhirClient,
        cache,
        config.getLocationTagSystem(),
        tagMutator,
        userAccessLevelTypeCode,
        userAssignedLocationId,
        config.getLeafLocationTypeCode(),
        ResourceType.fromCode(requestDetails.getResourceName()),
        false);
  }

  private AccessDecision processPatch(RequestDetailsReader requestDetails) throws IOException {
    // Patch keeps existing tags; ensure existing resource is accessible.
    return processRead(requestDetails);
  }

  private AccessDecision processDelete(RequestDetailsReader requestDetails) throws IOException {
    return processRead(requestDetails);
  }

  private AccessDecision processBundle(RequestDetailsReader requestDetails) throws IOException {
    Bundle bundle = FhirUtil.parseRequestToBundle(fhirContext, requestDetails);

    // Validate each entry.
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (!entry.hasRequest()) {
        return NoOpAccessDecision.accessDenied();
      }
      Bundle.BundleEntryRequestComponent req = entry.getRequest();
      if (req.getMethod() == null) {
        return NoOpAccessDecision.accessDenied();
      }

      switch (req.getMethod()) {
        case POST:
        case PUT:
        case PATCH:
          if (!entry.hasResource() || !(entry.getResource() instanceof Resource)) {
            return NoOpAccessDecision.accessDenied();
          }
          Resource r = (Resource) entry.getResource();
          boolean ok =
              !tagMutator
                  .computeTagsForWrite(
                      r,
                      userAssignedLocationId,
                      userAccessLevelTypeCode,
                      httpFhirClient,
                      fhirContext)
                  .isEmpty();
          if (!ok) {
            return NoOpAccessDecision.accessDenied();
          }
          break;
        case GET:
        case DELETE:
          // Best-effort validation: if resource included, validate tags. Otherwise skip.
          if (entry.hasResource() && entry.getResource() instanceof Resource) {
            Resource rr = (Resource) entry.getResource();
            if (!tagMutator.isAccessibleByTags(
                rr, userAssignedLocationId, userAccessLevelTypeCode, httpFhirClient, fhirContext)) {
              return NoOpAccessDecision.accessDenied();
            }
          }
          break;
        default:
          return NoOpAccessDecision.accessDenied();
      }
    }

    // Post-process response bundle to persist tags for entry resources.
    return LocationTaggingAccessDecision.persistTagsForWrite(
        fhirContext,
        httpFhirClient,
        cache,
        config.getLocationTagSystem(),
        tagMutator,
        userAccessLevelTypeCode,
        userAssignedLocationId,
        config.getLeafLocationTypeCode(),
        ResourceType.Bundle,
        false);
  }

  private static String extractPractitionerId(DecodedJWT jwt, LocationAccessConfig config) {
    String claimName = config.getPractitionerClaimName();
    String id = JwtUtil.getClaimOrDie(jwt, claimName);
    return FhirUtil.checkIdOrFail(id);
  }
}
