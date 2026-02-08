# AccessChecker plugins

To implement an access-checker plugin, the
[AccessCheckerFactory interface](../server/src/main/java/com/google/fhir/gateway/interfaces/AccessCheckerFactory.java)
must be implemented, and it must be annotated by a `@Named(value = "KEY")`
annotation. `KEY` is the name of the access-checker that can be used when
running the proxy server (by setting `ACCESS_CHECKER` environment variable).

Example access-checker plugins in this module are
[ListAccessChecker](src/main/java/com/google/fhir/gateway/plugin/ListAccessChecker.java),
[PatientAccessChecker](src/main/java/com/google/fhir/gateway/plugin/PatientAccessChecker.java),
and [LocationAccessCheckerPlugin](src/main/java/com/google/fhir/gateway/plugin/LocationAccessCheckerPlugin.java).
The **location** plugin enforces location-hierarchy-based access (search rewrite via `_tag`, tag validation, post-write tag persistence) and requires a Postgres cache: when using `ACCESS_CHECKER=location`, set `ENABLE_DATASOURCE=true` and configure a DataSource (e.g. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`).

Beside doing basic validation of the access-token, the server also provides some
query parameters and resource parsing functionality which are wrapped inside
[PatientFinder](../server/src/main/java/com/google/fhir/gateway/interfaces/PatientFinder.java).

<!--- Add some documentation about how each access-checker works. --->
