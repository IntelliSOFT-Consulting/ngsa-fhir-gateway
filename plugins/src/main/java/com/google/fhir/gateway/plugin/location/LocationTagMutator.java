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

import com.google.common.base.Preconditions;
import com.google.fhir.gateway.HttpFhirClient;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Resource;

/**
 * Injects and validates location tags in FHIR resources.
 *
 * <p>This plugin enforces access via GET search rewrite using {@code _tag}. To support hierarchical
 * filtering (e.g. SUB-COUNTY users filtering by their assigned SubCounty ID), resources must carry
 * tags that can be validated against the location hierarchy.
 *
 * <p>Tagging strategy:
 *
 * <ul>
 *   <li>Every resource is tagged with a leaf location (configured by {@code leafLocationTypeCode},
 *       e.g. {@code COMMUNITY-UNIT}).
 *   <li>For higher-level users (e.g. COUNTY), searches are rewritten by expanding to all leaf
 *       descendants.
 * </ul>
 */
final class LocationTagMutator {

  private final LocationAccessConfig config;
  private final LocationCachingService cache;

  LocationTagMutator(LocationAccessConfig config, LocationCachingService cache) {
    this.config = Preconditions.checkNotNull(config, "config");
    this.cache = Preconditions.checkNotNull(cache, "cache");
  }

  /** Returns all location tag codings on a resource (for this plugin system URL). */
  static List<Coding> getLocationTags(Resource resource, String system) {
    if (resource == null || !resource.hasMeta()) {
      return List.of();
    }
    return resource.getMeta().getTag().stream()
        .filter(c -> system.equals(c.getSystem()))
        .collect(Collectors.toList());
  }

  /** Extract facility id from tags (first matching tag). */
  static String extractFacilityIdFromTags(List<Coding> tags) {
    for (Coding c : tags) {
      String code = c.getCode();
      if (code == null || code.isBlank()) {
        continue;
      }
      if (code.startsWith("Location/")) {
        return code.substring("Location/".length());
      }
      return code;
    }
    return null;
  }

  /** Compute the full tag set (facility + ancestor ids) to apply to a resource. */
  Set<Coding> computeTagsForWrite(
      Resource resource,
      String userAssignedLocationId,
      String userAccessLevelTypeCode,
      HttpFhirClient httpFhirClient,
      ca.uhn.fhir.context.FhirContext fhirContext)
      throws IOException {
    Preconditions.checkNotNull(resource, "resource");
    Preconditions.checkNotNull(userAssignedLocationId, "userAssignedLocationId");
    Preconditions.checkNotNull(userAccessLevelTypeCode, "userAccessLevelTypeCode");
    Preconditions.checkNotNull(httpFhirClient, "httpFhirClient");
    Preconditions.checkNotNull(fhirContext, "fhirContext");

    List<Coding> existing = getLocationTags(resource, config.getLocationTagSystem());
    Set<Coding> tags = new HashSet<>();
    String leafType = config.getLeafLocationTypeCode();

    // If resource already has a location tag, require it to be within user's scope.
    String taggedLocationId = extractFacilityIdFromTags(existing);
    if (taggedLocationId != null) {
      String ancestorAtUserLevel =
          cache.findAncestorAtLevel(
              taggedLocationId, userAccessLevelTypeCode, httpFhirClient, fhirContext);
      if (ancestorAtUserLevel != null && ancestorAtUserLevel.equals(userAssignedLocationId)) {
        tags.add(toCoding("Location/" + taggedLocationId));
      }
      return tags;
    }

    // No tag on resource; only leaf-level users can auto-tag.
    if (!leafType.equals(userAccessLevelTypeCode)) {
      return Set.of();
    }

    tags.add(toCoding("Location/" + userAssignedLocationId));
    return tags;
  }

  /**
   * Applies tags to the in-memory resource object (used only for response shaping / patch calc).
   */
  void applyTags(Resource resource, Set<Coding> tags) {
    Preconditions.checkNotNull(resource, "resource");
    Preconditions.checkNotNull(tags, "tags");
    if (!resource.hasMeta()) {
      resource.setMeta(new Meta());
    }
    Meta meta = resource.getMeta();
    for (Coding c : tags) {
      meta.addTag(c);
    }
  }

  /**
   * Validates that a resource has at least one tag matching user's assigned location at their
   * level.
   */
  boolean isAccessibleByTags(
      Resource resource,
      String userAssignedLocationId,
      String userAccessLevelTypeCode,
      HttpFhirClient httpFhirClient,
      ca.uhn.fhir.context.FhirContext fhirContext)
      throws IOException {
    Preconditions.checkNotNull(resource, "resource");

    if (config.getRootLocationTypeCode().equals(userAccessLevelTypeCode)) {
      return true;
    }

    List<Coding> tags = getLocationTags(resource, config.getLocationTagSystem());
    if (tags.isEmpty()) {
      return false;
    }

    // Validate tag by walking up to user's level.
    for (Coding tag : tags) {
      String code = tag.getCode();
      if (code == null) {
        continue;
      }
      String taggedId = code.startsWith("Location/") ? code.substring("Location/".length()) : code;
      String ancestorAtUserLevel =
          cache.findAncestorAtLevel(taggedId, userAccessLevelTypeCode, httpFhirClient, fhirContext);
      if (ancestorAtUserLevel != null && ancestorAtUserLevel.equals(userAssignedLocationId)) {
        return true;
      }
    }
    return false;
  }

  private Coding toCoding(String code) {
    Coding c = new Coding();
    c.setSystem(config.getLocationTagSystem());
    c.setCode(code);
    return c;
  }
}
