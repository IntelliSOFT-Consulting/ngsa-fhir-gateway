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
package com.google.fhir.gateway.plugin;

import ca.uhn.fhir.context.FhirContext;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.fhir.gateway.HttpFhirClient;
import com.google.fhir.gateway.interfaces.AccessChecker;
import com.google.fhir.gateway.interfaces.AccessCheckerFactory;
import com.google.fhir.gateway.interfaces.PatientFinder;
import com.google.fhir.gateway.plugin.location.LocationAccessChecker;
import com.google.fhir.gateway.plugin.location.LocationAccessConfig;
import com.google.fhir.gateway.plugin.location.LocationCachingService;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * Entrypoint for the location-based access checker plugin.
 *
 * <p>Select this plugin by setting {@code ACCESS_CHECKER=location}.
 */
public final class LocationAccessCheckerPlugin {

  private LocationAccessCheckerPlugin() {}

  /** Factory for creating LocationAccessChecker instances from JWT tokens. */
  @Named(value = "location")
  public static final class Factory implements AccessCheckerFactory {

    private final LocationCachingService cachingService;
    private final LocationAccessConfig config;

    @Inject
    public Factory(LocationCachingService cachingService) {
      this.cachingService = cachingService;
      this.config = LocationAccessConfig.loadDefault();
    }

    @Override
    public AccessChecker create(
        DecodedJWT jwt,
        HttpFhirClient httpFhirClient,
        FhirContext fhirContext,
        PatientFinder patientFinder) {
      return new LocationAccessChecker(jwt, httpFhirClient, fhirContext, config, cachingService);
    }
  }
}
