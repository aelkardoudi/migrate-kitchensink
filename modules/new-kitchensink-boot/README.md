# new-kitchensink-boot

Spring Boot 4.1.2 runs the member read API on Java 21. The [core module](../new-kitchensink-core/README.md) defines the use cases. The H2 adapter uses Spring Data JPA to read the existing `Member` table. It uses the same H2 driver version as the legacy TCP server.

| Endpoint | Result |
| --- | --- |
| `GET /kitchensink/rest/members` | Members sorted by name, including members with the same name |
| `GET /kitchensink/rest/members/{id}` | Member details, or HTTP 404 for an unknown ID |

Both endpoints return `id`, `name`, `email`, and `phoneNumber` for each member. WildFly calls them for member reads and continues to handle registration and serve the JSF UI.

## H2 persistence

`H2MemberRepository` uses Spring Data JPA to find a member by ID and derive the list query ordered by name. `MemberEntity` maps the `Member` table, including the `phone_number` column and unique email constraint. Jakarta Validation requires a name of 1 to 25 characters without ASCII digits, a non-empty valid email and a phone number of 10 to 12 characters that satisfies `@Digits(integer = 12, fraction = 0)`.

Hibernate validates the existing schema at startup. The read API returns stored records, including historical data, independently of the entity's input validation rules.

## Build and test

Run from the repository root with JDK 21 and Maven.

```sh
mvn -Pmodern -pl .,modules/new-kitchensink-boot -am clean verify
```

The build produces `modules/new-kitchensink-boot/target/new-kitchensink-boot.jar`. The integration tests start the HTTP server with a test-only H2 database and check the read endpoints and entity constraints.

## Run and deploy

Follow the [Kubernetes guide](../../deploy/README.md#deploy-the-spring-member-reads) to start Spring Boot in its own Deployment, using the existing H2 service. The deployment provisions a dedicated read-only H2 account and exposes Actuator liveness and readiness probes. Readiness checks the database connection.

To run the JAR directly against an existing H2 server, set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` for a read-only account on that server.

```sh
java -jar modules/new-kitchensink-boot/target/new-kitchensink-boot.jar
```
