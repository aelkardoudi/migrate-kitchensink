# new-kitchensink-boot

Spring Boot runs the member API on Java 21. The [core module](../new-kitchensink-core/README.md) defines the member use cases.

| Endpoint | Result |
| --- | --- |
| `GET /kitchensink/rest/members` | Members sorted by name, including members with the same name |
| `GET /kitchensink/rest/members/{id}` | Member details, or HTTP 404 for an unknown ID |
| `POST /kitchensink/rest/members` | Accepts `name`, `email` and `phoneNumber`. Returns HTTP 200, 400 with field errors, or 409 for an existing email |

`MongoMemberRepository` serves reads and inserts. The unique email index prevents competing registrations from creating duplicate members. Each successful insertion uses a numeric ID allocated by an atomic increment of the MongoDB member counter.

From the repository root, use `mvn -pl modules/new-kitchensink-boot -am clean verify` to build the JAR and test the API contract. Use the [Kubernetes guide](../../deploy/README.md) to exercise the API with MongoDB through the public WildFly routes.

When running the JAR directly, supply `SPRING_MONGODB_URI` for the runtime account and `KS_MONGODB_MIGRATION_URI` for the Liquibase account. Both URIs must target the same database. The runtime account needs `find` and `insert` on `members` and `find` and `update` on `counters`. Spring Boot applies migrations before startup completes, and Actuator readiness checks that the member counter can allocate IDs.
