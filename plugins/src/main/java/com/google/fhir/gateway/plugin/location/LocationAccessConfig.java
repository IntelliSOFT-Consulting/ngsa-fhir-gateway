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
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Configuration for location-based access control. */
public final class LocationAccessConfig {

  private static final String DEFAULT_CONFIG_PATH = "/location/location-access-config.json";

  private Map<String, String> roleHierarchy;
  private String locationTagSystem;
  private String practitionerClaimName;
  private String locationExtensionUrl;
  private String roleExtensionUrl;
  private String rootLocationTypeCode;
  private String leafLocationTypeCode;
  private Integer maxDescendantTagsInSearch;

  private LocationAccessConfig() {}

  public static LocationAccessConfig loadDefault() {
    try {
      return loadFromClasspath(DEFAULT_CONFIG_PATH);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load location access config", e);
    }
  }

  static LocationAccessConfig loadFromClasspath(String path) throws IOException {
    try (InputStream in = LocationAccessConfig.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("Config not found on classpath: " + path);
      }
      try {
        LocationAccessConfig cfg =
            new Gson()
                .fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), LocationAccessConfig.class);
        cfg.validate();
        return cfg;
      } catch (JsonSyntaxException e) {
        throw new IOException("Invalid JSON config: " + path, e);
      }
    }
  }

  private void validate() throws IOException {
    if (roleHierarchy == null || roleHierarchy.isEmpty()) {
      throw new IOException("roleHierarchy is required");
    }
    requireNonEmpty(locationTagSystem, "locationTagSystem");
    requireNonEmpty(practitionerClaimName, "practitionerClaimName");
    requireNonEmpty(locationExtensionUrl, "locationExtensionUrl");
    requireNonEmpty(roleExtensionUrl, "roleExtensionUrl");
    requireNonEmpty(rootLocationTypeCode, "rootLocationTypeCode");
    requireNonEmpty(leafLocationTypeCode, "leafLocationTypeCode");
    if (maxDescendantTagsInSearch == null || maxDescendantTagsInSearch < 1) {
      throw new IOException("maxDescendantTagsInSearch must be >= 1");
    }
  }

  private static void requireNonEmpty(String v, String name) throws IOException {
    if (v == null || v.isBlank()) {
      throw new IOException(name + " is required");
    }
  }

  public Map<String, String> getRoleHierarchy() {
    return roleHierarchy;
  }

  public String getLocationTagSystem() {
    return locationTagSystem;
  }

  public String getPractitionerClaimName() {
    return practitionerClaimName;
  }

  public String getLocationExtensionUrl() {
    return locationExtensionUrl;
  }

  public String getRoleExtensionUrl() {
    return roleExtensionUrl;
  }

  /** Root level in the hierarchy (e.g. COUNTRY). */
  public String getRootLocationTypeCode() {
    return rootLocationTypeCode;
  }

  /** Returns the Location.type.coding.code level for this role. */
  public String accessLevelTypeCodeForRole(String role) {
    Preconditions.checkNotNull(role, "role");
    String typeCode = roleHierarchy.get(role);
    if (typeCode == null) {
      throw new IllegalArgumentException("Role not configured: " + role);
    }
    return typeCode;
  }

  /** Leaf level used for tagging resources (e.g. COMMUNITY-UNIT). */
  public String getLeafLocationTypeCode() {
    return leafLocationTypeCode;
  }

  /**
   * Maximum number of descendant leaf locations to expand into {@code _tag} filters for search
   * rewrite.
   */
  public int getMaxDescendantTagsInSearch() {
    return maxDescendantTagsInSearch;
  }
}
