# new-kitchensink-boot

Spring Boot 4.1.2 runs the member API on Java 21. The [core module](../new-kitchensink-core/README.md) defines the use cases. The default H2 adapter uses Spring Data JPA with the existing `Member` table. The `mongo` profile selects the Spring Data MongoDB adapter for reads and writes.

| Endpoint | Result |
| --- | --- |
| `GET /kitchensink/rest/members` | Members sorted by name, including members with the same name |
| `GET /kitchensink/rest/members/{id}` | Member details, or HTTP 404 for an unknown ID |
| `POST /kitchensink/rest/members` | Accepts `name`, `email` and `phoneNumber`. Returns HTTP 200 with an empty body, 400 with field errors, or 409 for an existing email |

The GET endpoints return `id`, `name`, `email`, and `phoneNumber` for each member. WildFly calls the API for member reads and for registration requests from JSF and REST.

## H2 persistence

`H2MemberRepository` uses Spring Data JPA to find a member by ID and derive the list query ordered by name. `MemberEntity` maps the `Member` table, including the `phone_number` column and unique email constraint. Jakarta Validation requires a name of 1 to 25 characters without ASCII digits, a non-empty valid email and a phone number of 10 to 12 characters that satisfies `@Digits(integer = 12, fraction = 0)`.

Hibernate validates the existing schema at startup. The read API returns historical records independently of the entity's input validation rules. Registration uses the existing `Member_SEQ` sequence and the database unique constraint on email.

## MongoDB persistence

`MongoMemberRepository` uses the `members` collection for listing, ID lookup and insertion. The Liquibase-managed unique email index ensures that concurrent requests with the same email produce one successful registration. An atomic increment of the `counters` document with `_id: "member"` allocates each new numeric ID. Before routing traffic to the MongoDB deployment, seed that document's `value` as a BSON Int64 with the maximum imported member ID. Actuator readiness checks that the counter is available and has capacity for another ID.

## Build and test

Run from the repository root with JDK 21 and Maven.

```sh
mvn -Pmodern -pl .,modules/new-kitchensink-boot -am clean verify
```

The build produces `modules/new-kitchensink-boot/target/new-kitchensink-boot.jar`. The integration tests start the HTTP server with a test-only H2 database and check the member API.

## Run and deploy

Follow the [Kubernetes guide](../../deploy/README.md#deploy-the-spring-member-api) to start Spring Boot in its own Deployment, using the existing H2 service. The deployment provisions a dedicated H2 account and exposes Actuator liveness and readiness probes. Readiness checks the database connection.

To run the JAR directly against an existing H2 server, set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` for an account with `SELECT` and `INSERT` on `Member`.

```sh
java -jar modules/new-kitchensink-boot/target/new-kitchensink-boot.jar
```

To run the JAR against MongoDB, set `SPRING_PROFILES_ACTIVE=mongo` and supply `SPRING_MONGODB_URI`. The account needs `find` and `insert` on `members` and `find` and `update` on `counters`. The database name in the URI selects the same database used for the backfill and relay.
