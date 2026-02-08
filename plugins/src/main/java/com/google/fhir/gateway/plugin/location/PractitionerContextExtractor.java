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
import java.util.List;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

/** Extracts role and assigned location from a Practitioner resource. */
final class PractitionerContextExtractor {

  private PractitionerContextExtractor() {}

  static String extractRole(Practitioner practitioner, LocationAccessConfig config) {
    Preconditions.checkNotNull(practitioner, "practitioner");
    Preconditions.checkNotNull(config, "config");
    List<Extension> exts = practitioner.getExtensionsByUrl(config.getRoleExtensionUrl());
    if (exts.isEmpty()) {
      throw new IllegalArgumentException(
          "No role extension found at url: " + config.getRoleExtensionUrl());
    }
    Extension e = exts.get(0);
    if (e.getValue() instanceof StringType) {
      return ((StringType) e.getValue()).getValue();
    }
    throw new IllegalArgumentException("Role extension value is not a StringType");
  }

  static String extractPrimaryLocationId(Practitioner practitioner, LocationAccessConfig config) {
    Preconditions.checkNotNull(practitioner, "practitioner");
    Preconditions.checkNotNull(config, "config");
    List<Extension> exts = practitioner.getExtensionsByUrl(config.getLocationExtensionUrl());
    if (exts.isEmpty()) {
      throw new IllegalArgumentException(
          "No location extension found at url: " + config.getLocationExtensionUrl());
    }
    Extension e = exts.get(0);
    if (e.getValue() instanceof Reference) {
      String ref = ((Reference) e.getValue()).getReference();
      if (ref == null) {
        throw new IllegalArgumentException("Location reference is null");
      }
      if (ref.startsWith("Location/")) {
        return ref.substring("Location/".length());
      }
      return ref;
    }
    throw new IllegalArgumentException("Location extension value is not a Reference");
  }
}
