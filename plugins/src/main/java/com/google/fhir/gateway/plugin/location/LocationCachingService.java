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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.fhir.gateway.FhirClientFactory;
import com.google.fhir.gateway.HttpFhirClient;
import com.google.fhir.gateway.HttpUtil;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Named;
import javax.sql.DataSource;
import org.apache.http.HttpResponse;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Postgres-backed cache for Location hierarchy and Practitioner context.
 *
 * <p>Design:
 *
 * <ul>
 *   <li>Full sync at startup in a background thread (non-blocking)
 *   <li>Fetch-through on misses (read from FHIR, write-through to Postgres)
 *   <li>TTL-based staleness (default 24h)
 *   <li>Event-driven updates for Location writes that pass through the gateway
 * </ul>
 */
@Named
public class LocationCachingService {

  private static final Logger logger = LoggerFactory.getLogger(LocationCachingService.class);

  // Default TTL chosen per user decision.
  private static final Duration DEFAULT_TTL = Duration.ofHours(24);

  // Note: schema is prefixed to avoid collisions with app tables.
  private static final String SCHEMA = "fhir_gateway_location_cache";

  private final DataSource dataSource;
  private final Duration ttl;
  private final Clock clock;
  private final ExecutorService syncExecutor;
  private final AtomicBoolean syncStarted = new AtomicBoolean(false);

  @Inject
  public LocationCachingService(DataSource dataSource) {
    this(dataSource, DEFAULT_TTL, Clock.systemUTC());
  }

  @VisibleForTesting
  LocationCachingService(DataSource dataSource, Duration ttl, Clock clock) {
    this.dataSource = Preconditions.checkNotNull(dataSource, "dataSource");
    this.ttl = Preconditions.checkNotNull(ttl, "ttl");
    this.clock = Preconditions.checkNotNull(clock, "clock");
    this.syncExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "location-cache-sync");
              t.setDaemon(true);
              return t;
            });
    ensureSchemaAndTables();
    deleteStaleRows();
    startNonBlockingFullSync();
  }

  /** Starts startup full sync (non-blocking). Safe to call multiple times. */
  public void startNonBlockingFullSync() {
    if (!syncStarted.compareAndSet(false, true)) {
      return;
    }
    syncExecutor.submit(
        () -> {
          try {
            HttpFhirClient client = FhirClientFactory.createFhirClientFromEnvVars();
            FhirContext ctx = FhirContext.forR4Cached();
            logger.info("Starting non-blocking Location full sync into Postgres.");
            String baseUrl = System.getenv("PROXY_TO");
            fullSyncLocations(client, ctx, baseUrl);
            logger.info("Completed Location full sync.");
          } catch (Exception e) {
            logger.error("Location full sync failed; continuing with fetch-through only.", e);
            syncStarted.set(false);
          }
        });
  }

  /** Upserts a Location into the cache (used by the change listener). */
  public void upsertLocation(Location location) {
    Preconditions.checkNotNull(location, "location");
    String id = location.getIdElement().getIdPart();
    if (id == null || id.isBlank()) {
      return;
    }
    String partOfId = extractLocationId(location.getPartOf());
    Set<String> typeCodes = extractLocationTypeCodes(location);
    Instant fetchedAt = clock.instant();

    try (Connection c = dataSource.getConnection()) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO "
                  + SCHEMA
                  + ".location (id, part_of_id, type_codes, fetched_at) "
                  + "VALUES (?, ?, ?, ?) "
                  + "ON CONFLICT (id) DO UPDATE SET "
                  + "part_of_id=EXCLUDED.part_of_id, "
                  + "type_codes=EXCLUDED.type_codes, "
                  + "fetched_at=EXCLUDED.fetched_at")) {
        ps.setString(1, id);
        ps.setString(2, partOfId);
        ps.setArray(3, c.createArrayOf("text", typeCodes.toArray(new String[0])));
        setInstant(ps, 4, fetchedAt);
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to upsert Location into cache", e);
    }
  }

  /** Deletes a Location from the cache (used by the change listener). */
  public void deleteLocation(String locationId) {
    if (locationId == null || locationId.isBlank()) {
      return;
    }
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("DELETE FROM " + SCHEMA + ".location WHERE id=?")) {
      ps.setString(1, locationId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to delete Location from cache", e);
    }
  }

  /** Returns practitioner role + assigned location (fetch-through + cached). */
  public PractitionerContext getPractitionerContext(
      String practitionerId,
      LocationAccessConfig config,
      HttpFhirClient httpFhirClient,
      FhirContext ctx)
      throws IOException {
    Preconditions.checkNotNull(practitionerId, "practitionerId");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(httpFhirClient, "httpFhirClient");
    Preconditions.checkNotNull(ctx, "ctx");

    PractitionerContext cached = readPractitionerContextIfFresh(practitionerId);
    if (cached != null) {
      return cached;
    }

    // Fetch-through from FHIR
    HttpResponse resp = httpFhirClient.getResource("Practitioner/" + practitionerId);
    HttpUtil.validateResponseEntityOrFail(resp, "Practitioner/" + practitionerId);
    Practitioner practitioner =
        (Practitioner) ctx.newJsonParser().parseResource(resp.getEntity().getContent());

    String role = PractitionerContextExtractor.extractRole(practitioner, config);
    String locationId = PractitionerContextExtractor.extractPrimaryLocationId(practitioner, config);

    PractitionerContext fresh = new PractitionerContext(role, locationId);
    writePractitionerContext(practitionerId, fresh);
    return fresh;
  }

  /**
   * Finds the ancestor Location ID at the desired access level by traversing upward using cached
   * {@code part_of_id}. Uses fetch-through to fill missing nodes.
   */
  public String findAncestorAtLevel(
      String startLocationId, String targetTypeCode, HttpFhirClient httpFhirClient, FhirContext ctx)
      throws IOException {
    Preconditions.checkNotNull(startLocationId, "startLocationId");
    Preconditions.checkNotNull(targetTypeCode, "targetTypeCode");
    Preconditions.checkNotNull(httpFhirClient, "httpFhirClient");
    Preconditions.checkNotNull(ctx, "ctx");

    // First try using cache-only recursive query.
    Optional<String> fromCache = findAncestorAtTypeCached(startLocationId, targetTypeCode);
    if (fromCache.isPresent()) {
      return fromCache.get();
    }

    // Fetch-through: ensure the chain is cached (bounded).
    ensureLocationChainCached(startLocationId, httpFhirClient, ctx, 30);

    return findAncestorAtTypeCached(startLocationId, targetTypeCode).orElse(null);
  }

  /**
   * Returns descendant Location IDs (including the start node if it matches) whose {@code
   * Location.type.coding.code} contains {@code desiredTypeCode}.
   *
   * <p>This is used to expand a user's assigned location (e.g. County) into all leaf nodes (e.g.
   * Community Units) for query rewrite.
   *
   * <p>Results are bounded by {@code limit}. If there are more than {@code limit} matches, this
   * returns {@code limit+1} items (caller can detect overflow and fail closed).
   */
  public List<String> getDescendantIdsByType(
      String startLocationId, String desiredTypeCode, int limit) {
    Preconditions.checkNotNull(startLocationId, "startLocationId");
    Preconditions.checkNotNull(desiredTypeCode, "desiredTypeCode");
    Preconditions.checkArgument(limit > 0, "limit must be > 0");

    Instant cutoff = clock.instant().minus(ttl);
    List<String> out = new java.util.ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "WITH RECURSIVE d AS ("
                    + "  SELECT id, part_of_id, type_codes, fetched_at FROM "
                    + SCHEMA
                    + ".location WHERE id=? "
                    + "  UNION ALL "
                    + "  SELECT l.id, l.part_of_id, l.type_codes, l.fetched_at FROM "
                    + SCHEMA
                    + ".location l "
                    + "  JOIN d ON l.part_of_id = d.id "
                    + ") CYCLE id SET is_cycle USING cycle_path "
                    + "SELECT id FROM d "
                    + "WHERE ? = ANY(type_codes) AND fetched_at >= ? AND NOT is_cycle "
                    + "LIMIT ?")) {
      ps.setString(1, startLocationId);
      ps.setString(2, desiredTypeCode);
      setInstant(ps, 3, cutoff);
      ps.setInt(4, limit + 1);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to query descendant locations", e);
    }
  }

  /**
   * Update cache based on successful proxied Location mutation.
   *
   * @param request the original request details
   * @param responseContent the already-read response body (may be null or blank for DELETE)
   * @param httpFhirClient client for fallback fetches
   * @param ctx FHIR context for parsing
   */
  public void handleLocationWritePostProcess(
      RequestDetailsReader request,
      String responseContent,
      HttpFhirClient httpFhirClient,
      FhirContext ctx)
      throws IOException {
    Preconditions.checkNotNull(request, "request");
    Preconditions.checkNotNull(httpFhirClient, "httpFhirClient");
    Preconditions.checkNotNull(ctx, "ctx");

    if (request.getRequestType() == null) {
      return;
    }

    switch (request.getRequestType()) {
      case DELETE:
        if (request.getId() != null) {
          deleteLocation(request.getId().getIdPart());
        }
        return;
      case POST:
      case PUT:
      case PATCH:
        Location loc = parseOrFetchLocation(request, responseContent, httpFhirClient, ctx);
        if (loc != null) {
          upsertLocation(loc);
        }
        return;
      default:
        return;
    }
  }

  private Location parseOrFetchLocation(
      RequestDetailsReader request,
      String responseContent,
      HttpFhirClient httpFhirClient,
      FhirContext ctx)
      throws IOException {
    if (responseContent != null && !responseContent.isBlank()) {
      try {
        return (Location) ctx.newJsonParser().parseResource(responseContent);
      } catch (Exception e) {
        // Fall back to fetch.
      }
    }

    if (request.getId() == null || request.getId().getIdPart() == null) {
      return null;
    }
    String id = request.getId().getIdPart();
    HttpResponse resp = httpFhirClient.getResource("Location/" + id);
    HttpUtil.validateResponseEntityOrFail(resp, "Location/" + id);
    return (Location) ctx.newJsonParser().parseResource(resp.getEntity().getContent());
  }

  private PractitionerContext readPractitionerContextIfFresh(String practitionerId) {
    Instant cutoff = clock.instant().minus(ttl);
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT role, primary_location_id, fetched_at FROM "
                    + SCHEMA
                    + ".practitioner_context WHERE id=?")) {
      ps.setString(1, practitionerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        Timestamp ts = rs.getTimestamp(3);
        Instant fetchedAt = ts != null ? ts.toInstant() : null;
        if (fetchedAt == null || fetchedAt.isBefore(cutoff)) {
          return null;
        }
        return new PractitionerContext(rs.getString(1), rs.getString(2));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read practitioner context cache", e);
    }
  }

  private void writePractitionerContext(String practitionerId, PractitionerContext ctx) {
    Instant now = clock.instant();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO "
                    + SCHEMA
                    + ".practitioner_context (id, role, primary_location_id, fetched_at) VALUES (?,"
                    + " ?, ?, ?) ON CONFLICT (id) DO UPDATE SET role=EXCLUDED.role,"
                    + " primary_location_id=EXCLUDED.primary_location_id,"
                    + " fetched_at=EXCLUDED.fetched_at")) {
      ps.setString(1, practitionerId);
      ps.setString(2, ctx.role());
      ps.setString(3, ctx.primaryLocationId());
      setInstant(ps, 4, now);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to write practitioner context cache", e);
    }
  }

  private Optional<String> findAncestorAtTypeCached(
      String startLocationId, String desiredTypeCode) {
    Instant cutoff = clock.instant().minus(ttl);
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "WITH RECURSIVE chain AS ("
                    + "  SELECT id, part_of_id, type_codes, fetched_at FROM "
                    + SCHEMA
                    + ".location WHERE id=? "
                    + "  UNION ALL "
                    + "  SELECT l.id, l.part_of_id, l.type_codes, l.fetched_at FROM "
                    + SCHEMA
                    + ".location l "
                    + "  JOIN chain c ON l.id = c.part_of_id "
                    + ") CYCLE id SET is_cycle USING cycle_path "
                    + "SELECT id, type_codes, fetched_at FROM chain WHERE NOT is_cycle")) {
      ps.setString(1, startLocationId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Timestamp ts3 = rs.getTimestamp(3);
          Instant fetchedAt = ts3 != null ? ts3.toInstant() : null;
          if (fetchedAt == null || fetchedAt.isBefore(cutoff)) {
            // Treat stale rows as missing.
            return Optional.empty();
          }
          String[] typeCodes = (String[]) rs.getArray(2).getArray();
          for (String t : typeCodes) {
            if (Objects.equals(t, desiredTypeCode)) {
              return Optional.of(rs.getString(1));
            }
          }
        }
      }
      return Optional.empty();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to query cached ancestor chain", e);
    }
  }

  private void ensureLocationChainCached(
      String startId, HttpFhirClient httpFhirClient, FhirContext ctx, int maxDepth)
      throws IOException {
    Set<String> visited = new HashSet<>();
    String current = startId;
    int depth = 0;
    while (current != null && depth < maxDepth) {
      if (!visited.add(current)) {
        return;
      }
      if (isLocationFresh(current)) {
        // If fresh, continue from cached parent.
        current = readPartOfId(current);
        depth++;
        continue;
      }

      // Fetch-through from FHIR and upsert.
      HttpResponse resp = httpFhirClient.getResource("Location/" + current);
      HttpUtil.validateResponseEntityOrFail(resp, "Location/" + current);
      Location loc = (Location) ctx.newJsonParser().parseResource(resp.getEntity().getContent());
      upsertLocation(loc);
      current = extractLocationId(loc.getPartOf());
      depth++;
    }
  }

  private boolean isLocationFresh(String id) {
    Instant cutoff = clock.instant().minus(ttl);
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT fetched_at FROM " + SCHEMA + ".location WHERE id=?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return false;
        }
        Timestamp ts1 = rs.getTimestamp(1);
        Instant fetchedAt = ts1 != null ? ts1.toInstant() : null;
        return fetchedAt != null && !fetchedAt.isBefore(cutoff);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to query location freshness", e);
    }
  }

  private String readPartOfId(String id) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT part_of_id FROM " + SCHEMA + ".location WHERE id=?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return rs.getString(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read part_of_id from cache", e);
    }
  }

  private void ensureSchemaAndTables() {
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
      st.execute(
          "CREATE TABLE IF NOT EXISTS "
              + SCHEMA
              + ".location ("
              + "id TEXT PRIMARY KEY,"
              + "part_of_id TEXT NULL,"
              + "type_codes TEXT[] NOT NULL DEFAULT '{}',"
              + "fetched_at TIMESTAMPTZ NOT NULL"
              + ")");
      st.execute(
          "CREATE INDEX IF NOT EXISTS idx_location_part_of ON " + SCHEMA + ".location(part_of_id)");
      st.execute(
          "CREATE INDEX IF NOT EXISTS idx_location_type_codes ON "
              + SCHEMA
              + ".location USING GIN (type_codes)");
      st.execute(
          "CREATE INDEX IF NOT EXISTS idx_location_fetched_at ON "
              + SCHEMA
              + ".location (fetched_at)");
      st.execute(
          "CREATE INDEX IF NOT EXISTS idx_location_part_of_fetched ON "
              + SCHEMA
              + ".location (part_of_id, fetched_at)");
      st.execute(
          "CREATE TABLE IF NOT EXISTS "
              + SCHEMA
              + ".practitioner_context ("
              + "id TEXT PRIMARY KEY,"
              + "role TEXT NOT NULL,"
              + "primary_location_id TEXT NOT NULL,"
              + "fetched_at TIMESTAMPTZ NOT NULL"
              + ")");
      st.execute(
          "CREATE INDEX IF NOT EXISTS idx_practitioner_context_fetched_at ON "
              + SCHEMA
              + ".practitioner_context (fetched_at)");
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to initialize cache schema/tables", e);
    }
  }

  /** Deletes rows older than TTL. Call on startup or periodically to keep tables small. */
  public void deleteStaleRows() {
    Instant cutoff = clock.instant().minus(ttl);
    try (Connection c = dataSource.getConnection();
        PreparedStatement psLoc =
            c.prepareStatement("DELETE FROM " + SCHEMA + ".location WHERE fetched_at < ?");
        PreparedStatement psPrac =
            c.prepareStatement(
                "DELETE FROM " + SCHEMA + ".practitioner_context WHERE fetched_at < ?")) {
      setInstant(psLoc, 1, cutoff);
      int locDeleted = psLoc.executeUpdate();
      setInstant(psPrac, 1, cutoff);
      int pracDeleted = psPrac.executeUpdate();
      if (locDeleted > 0 || pracDeleted > 0) {
        logger.info(
            "Cache TTL cleanup: deleted {} location rows, {} practitioner_context rows",
            locDeleted,
            pracDeleted);
      }
    } catch (SQLException e) {
      logger.warn("Cache TTL cleanup failed (non-fatal)", e);
    }
  }

  private static void setInstant(PreparedStatement ps, int parameterIndex, Instant instant)
      throws SQLException {
    if (instant == null) {
      ps.setTimestamp(parameterIndex, null);
    } else {
      ps.setTimestamp(parameterIndex, Timestamp.from(instant));
    }
  }

  private void fullSyncLocations(HttpFhirClient client, FhirContext ctx, String baseUrl)
      throws IOException {
    // Pull all Locations using paging. We only need id/partOf/type for hierarchy traversal.
    String resourcePath = "Location?_count=200&_elements=id,partOf,type";
    int pages = 0;
    while (resourcePath != null && pages < 10_000) {
      pages++;
      HttpResponse resp = client.getResource(resourcePath);
      HttpUtil.validateResponseEntityOrFail(resp, resourcePath);
      Bundle bundle = (Bundle) ctx.newJsonParser().parseResource(resp.getEntity().getContent());
      for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
        if (entry.getResource() instanceof Location) {
          upsertLocation((Location) entry.getResource());
        }
      }
      resourcePath = nextPageResourcePath(bundle, baseUrl);
    }
  }

  private static String nextPageResourcePath(Bundle bundle, String baseUrl) {
    if (bundle == null || bundle.getLink() == null) {
      return null;
    }
    String nextUrl = null;
    for (Bundle.BundleLinkComponent link : bundle.getLink()) {
      if ("next".equals(link.getRelation())) {
        nextUrl = link.getUrl();
        break;
      }
    }
    if (nextUrl == null || nextUrl.isBlank()) {
      return null;
    }
    try {
      java.net.URI nextUri = java.net.URI.create(nextUrl);
      if (baseUrl == null || baseUrl.isBlank()) {
        // Best-effort fallback: return only the next path+query.
        String p = Optional.ofNullable(nextUri.getPath()).orElse("");
        if (p.startsWith("/")) {
          p = p.substring(1);
        }
        String q = nextUri.getQuery();
        return q == null ? p : p + "?" + q;
      }
      java.net.URI baseUri = java.net.URI.create(baseUrl);
      String basePath = Optional.ofNullable(baseUri.getPath()).orElse("");
      String nextPath = Optional.ofNullable(nextUri.getPath()).orElse("");
      String relPath = nextPath;
      if (!basePath.isEmpty() && relPath.startsWith(basePath)) {
        relPath = relPath.substring(basePath.length());
      }
      if (relPath.startsWith("/")) {
        relPath = relPath.substring(1);
      }
      String query = nextUri.getQuery();
      if (relPath.isEmpty()) {
        return query == null ? null : "?" + query;
      }
      return query == null ? relPath : relPath + "?" + query;
    } catch (Exception e) {
      logger.warn("Could not parse next link url: {}", nextUrl);
      return null;
    }
  }

  private static Set<String> extractLocationTypeCodes(Location location) {
    Set<String> codes = new HashSet<>();
    if (location == null || !location.hasType()) {
      return codes;
    }
    location
        .getType()
        .forEach(
            cc -> {
              if (cc.hasCoding()) {
                cc.getCoding()
                    .forEach(
                        coding -> {
                          if (coding.hasCode()) {
                            codes.add(coding.getCode());
                          }
                        });
              }
            });
    return codes;
  }

  private static String extractLocationId(Reference reference) {
    if (reference == null || reference.getReference() == null) {
      return null;
    }
    String ref = reference.getReference();
    if (ref.startsWith("Location/")) {
      return ref.substring("Location/".length());
    }
    return ref;
  }

  /** Value object for practitioner context. */
  public static final class PractitionerContext {
    private final String role;
    private final String primaryLocationId;

    public PractitionerContext(String role, String primaryLocationId) {
      this.role = role;
      this.primaryLocationId = primaryLocationId;
    }

    public String role() {
      return role;
    }

    public String primaryLocationId() {
      return primaryLocationId;
    }
  }
}
