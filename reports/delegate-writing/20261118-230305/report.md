# Delegated member registration test report

**20 tests passed.** The Playwright run started on 2026-11-18 23:03:05 CET in `migrate-kitchensink-test` on `kind-kitchensink`. The REST and browser tests went through WildFly. WildFly delegated both member reads and registrations to Spring Boot. Spring Boot read and wrote to the shared H2 database.

| Suite | Result | Evidence |
| --- | --- | --- |
| Playwright REST contract | 11/11. Covers sorted listing, homonyms, detail, missing IDs, field validation, duplicate email and concurrent registrations. | [Playwright report](playwright.json) |
| Playwright Chromium E2E | 1/1. Covers JSF form validation, registration, immediate listing, detail and duplicate handling. | [Playwright report](playwright.json) |
| Spring Boot integration | 8/8. Covers reads, field validation, creation, duplicate email and concurrent registration against H2. | [Maven result](spring-integration.txt) |

Spring MVC logged 17 member registration requests from WildFly. The responses were eight `200 OK`, six `400 BAD_REQUEST` and three `409 CONFLICT`. Concurrent requests for the same email returned `200` and `409`. The contract test confirmed that H2 contained one matching member. [Spring HTTP log](spring-http.log)

| Deployment | Image used in the run |
| --- | --- |
| WildFly | `migrate-kitchensink-legacy:content-826f2cd57219d511b98eb86bf0961487b7622e69c7277139659f3ca49b1ef713` |
| Spring Boot | `migrate-kitchensink-modern:content-112b3ba9e2347de8a282f64da4d9b907ad0a0aead27dc318ed74af619c729067` |
| H2 | `migrate-kitchensink-h2:content-d57a868e0a5a326cbf177accf071eb586c03c8489d2912177834067961d73c2a` |

The Playwright JSON preserves the original test results and timings. Local checkout paths use repository-relative paths and the Node executable is recorded as `node`. The Spring HTTP log contains the registration requests and responses paired by worker. The Maven result contains the integration test summary.

With JDK 21, build Spring and run both Playwright projects from the repository root. The Playwright command uses the local Kubernetes test namespace and the images prepared by the deployment scripts. [Deployment guide](../../../deploy/README.md) · [Playwright instructions](../../../e2e/README.md)

```sh
mvn -B -Pmodern clean verify
./deploy/build-images.sh
./deploy/modern.sh build
npm --prefix e2e test
```
