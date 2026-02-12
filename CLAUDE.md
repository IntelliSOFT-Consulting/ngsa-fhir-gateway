# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

## Project Overview

FHIR Information Gateway — a Java-based reverse proxy that enforces access
control between FHIR clients and FHIR servers. Uses JWT-based authentication and
a plugin architecture for extensible access checking.

## Build & Development Commands

```bash
# Build (includes Spotless format check + tests)
mvn package

# Build skipping format checks (faster iteration)
mvn package -Dspotless.apply.skip=true

# Run tests only
mvn test

# Run a single test class
mvn test -pl server -Dtest=BearerAuthorizationInterceptorTest

# Run a single test method
mvn test -pl server -Dtest=BearerAuthorizationInterceptorTest#validTokenShouldPass

# Auto-format code (Google Java Format)
mvn spotless:apply

# Check formatting without fixing
mvn spotless:check

# Install to local repo (skip tests)
mvn install -DskipTests=true -Dmaven.javadoc.skip=true

# E2E tests (requires Docker)
./e2e-test/e2e.sh
```

## Module Structure

Maven multi-module project:

- **server/** — Core gateway: FHIR proxy server, JWT auth interceptor, request
  routing, plugin interfaces. This is where `AccessCheckerFactory`,
  `AccessChecker`, `AccessDecision`, and `RequestMutation` interfaces live
  (under `server/.../interfaces/`).
- **plugins/** — Access checker implementations: `patient` (SMART-on-FHIR
  scopes), `list` (list-based ACL), `location` (hierarchical location with
  Postgres cache).
- **exec/** — Spring Boot entry point (`MainApp.java`). Produces the executable
  JAR. Scans `com.google.fhir.gateway.plugin` for plugin auto-discovery.
- **coverage/** — JaCoCo coverage aggregation module.

## Architecture

### Request Flow

```
Client → BearerAuthorizationInterceptor (JWT validation)
       → AccessChecker.checkAccess(request) → AccessDecision
       → HttpFhirClient/GcpFhirClient → FHIR Backend
       → AccessDecision.postProcess(response)
```

### Plugin System

Plugins implement `AccessCheckerFactory` with `@Named("pluginName")`. Selected
at runtime via `ACCESS_CHECKER` env var. Each request creates an `AccessChecker`
from the JWT, which returns an `AccessDecision` that can:

- Grant/deny access (`canAccess()`)
- Rewrite queries (`getRequestMutation()` — e.g., inject `_tag` params)
- Post-process responses (`postProcess()`)

### Location Plugin (most complex)

Uses Postgres to cache FHIR Location hierarchy. On startup, syncs all Locations.
Enforces access by role-to-location-level mapping (e.g., `COUNTY_ADMINISTRATOR`
→ `COUNTY`). Rewrites searches with `_tag` filters for descendant locations.
Config: `plugins/src/main/resources/location/location-access-config.json`.

## Key Environment Variables

| Variable               | Purpose                                       |
| ---------------------- | --------------------------------------------- |
| `PROXY_TO`             | FHIR backend URL                              |
| `TOKEN_ISSUER`         | JWT issuer (e.g., Keycloak realm URL)         |
| `ACCESS_CHECKER`       | Plugin name: `list`, `patient`, or `location` |
| `BACKEND_TYPE`         | `HAPI` or `GCP`                               |
| `RUN_MODE`             | `PROD` (enforce issuer) or `DEV` (bypass)     |
| `ALLOWED_QUERIES_FILE` | Path to query allowlist JSON                  |
| `ENABLE_DATASOURCE`    | `true` to enable Postgres (location plugin)   |

## Code Style

- Google Java Format enforced via Spotless (runs on `mvn compile`)
- Apache 2.0 license headers auto-applied via `license-maven-plugin`
- Run `mvn spotless:apply` before committing to fix formatting
- Uses Lombok for boilerplate reduction
- DI via `@Inject`/`@Named` (javax.inject), wired by Spring Boot

## Testing

- Unit tests: JUnit 4 + Mockito + Hamcrest
- Use `-pl <module>` to scope test runs to a specific module (e.g.,
  `-pl plugins`)
- E2E tests are Python-based in `/e2e-test/`
