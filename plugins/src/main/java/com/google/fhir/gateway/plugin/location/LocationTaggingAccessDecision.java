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
import ca.uhn.fhir.parser.IParser;
import com.google.common.base.Preconditions;
import com.google.common.io.CharStreams;
import com.google.fhir.gateway.HttpFhirClient;
import com.google.fhir.gateway.HttpUtil;
import com.google.fhir.gateway.interfaces.AccessDecision;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import com.google.fhir.gateway.interfaces.RequestMutation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.http.HttpResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AccessDecision that:
 *
 * <ul>
 *   <li>Optionally mutates query params (search rewrite)
 *   <li>After successful writes, PATCHes the stored resource(s) to persist tags
 *   <li>Optionally updates the Location cache for Location mutations
 * </ul>
 */
final class LocationTaggingAccessDecision implements AccessDecision {

  private static final Logger logger = LoggerFactory.getLogger(LocationTaggingAccessDecision.class);

  private final boolean canAccess;
  private final RequestMutation mutation;

  // Post-processing.
  private final FhirContext fhirContext;
  private final HttpFhirClient httpFhirClient;
  private final LocationCachingService cache;

  private final String tagSystem;
  private final LocationTagMutator tagMutator;
  private final String userAccessLevelTypeCode;
  private final String userAssignedLocationId;
  private final String leafLocationTypeCode;
  private final ResourceType expectedResourceType;

  private final boolean handleLocationCacheUpdates;
  private final String practitionerId;

  private LocationTaggingAccessDecision(
      boolean canAccess,
      RequestMutation mutation,
      FhirContext fhirContext,
      HttpFhirClient httpFhirClient,
      LocationCachingService cache,
      String tagSystem,
      LocationTagMutator tagMutator,
      String userAccessLevelTypeCode,
      String userAssignedLocationId,
      String leafLocationTypeCode,
      ResourceType expectedResourceType,
      boolean handleLocationCacheUpdates,
      String practitionerId) {
    this.canAccess = canAccess;
    this.mutation = mutation;
    this.fhirContext = fhirContext;
    this.httpFhirClient = httpFhirClient;
    this.cache = cache;
    this.tagSystem = tagSystem;
    this.tagMutator = tagMutator;
    this.userAccessLevelTypeCode = userAccessLevelTypeCode;
    this.userAssignedLocationId = userAssignedLocationId;
    this.leafLocationTypeCode = leafLocationTypeCode;
    this.expectedResourceType = expectedResourceType;
    this.handleLocationCacheUpdates = handleLocationCacheUpdates;
    this.practitionerId = practitionerId;
  }

  static LocationTaggingAccessDecision deny() {
    return new LocationTaggingAccessDecision(
        false, null, null, null, null, null, null, null, null, null, null, false, null);
  }

  static LocationTaggingAccessDecision allow() {
    return new LocationTaggingAccessDecision(
        true, null, null, null, null, null, null, null, null, null, null, false, null);
  }

  static LocationTaggingAccessDecision withSearchMutation(
      RequestMutation mutation, String practitionerId) {
    return new LocationTaggingAccessDecision(
        true,
        mutation,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        practitionerId);
  }

  static LocationTaggingAccessDecision persistTagsForWrite(
      FhirContext fhirContext,
      HttpFhirClient httpFhirClient,
      LocationCachingService cache,
      String tagSystem,
      LocationTagMutator tagMutator,
      String userAccessLevelTypeCode,
      String userAssignedLocationId,
      String leafLocationTypeCode,
      ResourceType expectedResourceType,
      boolean handleLocationCacheUpdates,
      String practitionerId) {
    return new LocationTaggingAccessDecision(
        true,
        null,
        fhirContext,
        httpFhirClient,
        cache,
        tagSystem,
        tagMutator,
        userAccessLevelTypeCode,
        userAssignedLocationId,
        leafLocationTypeCode,
        expectedResourceType,
        handleLocationCacheUpdates,
        practitionerId);
  }

  @Override
  public boolean canAccess() {
    return canAccess;
  }

  @Override
  public RequestMutation getRequestMutation(RequestDetailsReader requestDetailsReader) {
    return mutation;
  }

  @Override
  public Reference getUserWho(RequestDetailsReader request) {
    if (practitionerId != null) {
      return new Reference("Practitioner/" + practitionerId);
    }
    return AccessDecision.super.getUserWho(request);
  }

  @Override
  public String postProcess(RequestDetailsReader request, HttpResponse response)
      throws IOException {
    // No-op if not configured.
    if (fhirContext == null || httpFhirClient == null) {
      return null;
    }

    Preconditions.checkState(HttpUtil.isResponseValid(response));

    String content = CharStreams.toString(HttpUtil.readerFromEntity(response.getEntity()));
    if (content == null || content.isBlank()) {
      // Some operations may return empty bodies (e.g. DELETE). Still allow cache update.
      if (handleLocationCacheUpdates && cache != null) {
        cache.handleLocationWritePostProcess(request, content, httpFhirClient, fhirContext);
      }
      return content;
    }

    IParser parser = fhirContext.newJsonParser();
    IBaseResource parsed = parser.parseResource(content);
    if (expectedResourceType != null
        && !parsed.fhirType().equals(expectedResourceType.name())
        && !(expectedResourceType == ResourceType.Bundle && parsed instanceof Bundle)) {
      logger.warn(
          "Expected resource type {} but got {}; skipping tag persistence.",
          expectedResourceType,
          parsed.fhirType());
      return content;
    }

    if (handleLocationCacheUpdates && cache != null) {
      cache.handleLocationWritePostProcess(request, content, httpFhirClient, fhirContext);
    }

    // Persist tags for normal resources (best-effort).
    if (tagMutator == null) {
      return content;
    }

    if (parsed instanceof Resource) {
      persistTagsForResource((Resource) parsed);
    } else if (parsed instanceof Bundle) {
      persistTagsForBundle((Bundle) parsed);
    }

    return content;
  }

  private void persistTagsForBundle(Bundle bundle) {
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (entry.hasResource() && entry.getResource() instanceof Resource) {
        persistTagsForResource((Resource) entry.getResource());
      }
    }
  }

  private void persistTagsForResource(Resource resource) {
    IIdType id = resource.getIdElement();
    if (id == null || id.getResourceType() == null || id.getIdPart() == null) {
      return;
    }
    String resourcePath = id.getResourceType() + "/" + id.getIdPart();
    String jsonPatch = buildMetaTagJsonPatch(resource);
    if (jsonPatch == null) {
      return;
    }
    try {
      httpFhirClient.patchResource(resourcePath, jsonPatch);
    } catch (Exception e) {
      // Don't fail the original request; best-effort.
      logger.error("Failed to PATCH tags for {}", resourcePath, e);
    }
  }

  /**
   * Builds a JSON Patch to add missing meta.tag codings.
   *
   * <p>Note: JSON Patch uses RFC6902 and HAPI uses content-type application/json-patch+json.
   */
  private String buildMetaTagJsonPatch(Resource resource) {
    if (resource == null) {
      return null;
    }

    Set<Coding> desired;
    try {
      desired =
          tagMutator.computeTagsForWrite(
              resource,
              userAssignedLocationId,
              userAccessLevelTypeCode,
              httpFhirClient,
              fhirContext);
    } catch (IOException e) {
      logger.error("Failed computing tags for {}", resource.fhirType(), e);
      return null;
    }
    if (desired.isEmpty()) {
      return null;
    }

    List<Coding> existing =
        resource.hasMeta()
            ? resource.getMeta().getTag().stream()
                .filter(c -> tagSystem.equals(c.getSystem()))
                .collect(Collectors.toList())
            : List.of();

    List<Coding> missing = new ArrayList<>();
    for (Coding d : desired) {
      boolean present =
          existing.stream()
              .anyMatch(
                  e -> tagSystem.equals(e.getSystem()) && Objects.equals(e.getCode(), d.getCode()));
      if (!present) {
        missing.add(d);
      }
    }
    if (missing.isEmpty()) {
      return null;
    }

    boolean hasMeta = resource.hasMeta();
    boolean hasAnyTag = hasMeta && resource.getMeta().hasTag();

    StringBuilder sb = new StringBuilder();
    sb.append("[");
    boolean firstOp = true;

    if (!hasMeta) {
      // /meta doesn't exist at all — add the whole meta object.
      sb.append("{\"op\":\"add\",\"path\":\"/meta\",\"value\":{\"tag\":[");
      boolean firstVal = true;
      for (Coding c : missing) {
        if (!firstVal) {
          sb.append(",");
        }
        sb.append("{\"system\":\"")
            .append(escapeJson(tagSystem))
            .append("\",\"code\":\"")
            .append(escapeJson(c.getCode()))
            .append("\"}");
        firstVal = false;
      }
      sb.append("]}}");
      firstOp = false;
    } else if (!hasAnyTag) {
      // /meta exists but has no tag array — add the tag array.
      sb.append("{\"op\":\"add\",\"path\":\"/meta/tag\",\"value\":[");
      boolean firstVal = true;
      for (Coding c : missing) {
        if (!firstVal) {
          sb.append(",");
        }
        sb.append("{\"system\":\"")
            .append(escapeJson(tagSystem))
            .append("\",\"code\":\"")
            .append(escapeJson(c.getCode()))
            .append("\"}");
        firstVal = false;
      }
      sb.append("]}");
      firstOp = false;
    } else {
      for (Coding c : missing) {
        if (!firstOp) {
          sb.append(",");
        }
        sb.append("{\"op\":\"add\",\"path\":\"/meta/tag/-\",\"value\":")
            .append("{\"system\":\"")
            .append(escapeJson(tagSystem))
            .append("\",\"code\":\"")
            .append(escapeJson(c.getCode()))
            .append("\"}}");
        firstOp = false;
      }
    }

    sb.append("]");
    return sb.toString();
  }

  private static String escapeJson(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
