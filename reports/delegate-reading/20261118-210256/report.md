# Delegated member reads test report

**17 tests passed.** The Playwright run started on 18 November 2026 at 21:02:55 CET in `migrate-kitchensink-dev` on `kind-kitchensink`. Playwright targeted the WildFly pod. WildFly delegated the member list and detail reads to Spring Boot, which read the shared H2 database. WildFly handled registrations.

| Suite | Result | Evidence |
| --- | --- | --- |
| Playwright REST contract | 11/11. Covers sorted listing, homonyms, detail, missing IDs, field validation, duplicate email and concurrent registrations. | [Playwright report](playwright.json) |
| Playwright Chromium E2E | 1/1. Covers form validation, registration, immediate listing, detail and duplicate handling. | [Playwright report](playwright.json) |
| Spring Boot integration | 4/4. Covers list order, detail, missing or out-of-range identifiers and legacy validation rules on the JPA entity. | [Maven result](spring-integration.txt) |
| WildFly registration integration | 1/1. A valid registration returns HTTP 200 with an empty response body. | [JUnit result](legacy-junit.txt) |

During the Playwright run, Spring MVC logged 21 member reads as request and completion pairs. The completions include twenty `200 OK` responses and one `404 NOT_FOUND` response. [Spring HTTP log](spring-http.log)

| Deployment | Image used in the run |
| --- | --- |
| WildFly | `migrate-kitchensink-legacy:content-9baabf303867438c0df1e736c10907a73d22ae4cfe1631c91cada231b25b8006` |
| Spring Boot | `migrate-kitchensink-modern:content-757eb2e4bd491a7e780f517dd531865412538317bf8946ce50a9438a3181ca2f` |
| H2 | `migrate-kitchensink-h2:content-d57a868e0a5a326cbf177accf071eb586c03c8489d2912177834067961d73c2a` |

`RemoteMemberRegistrationIT` ran directly under JUnit 4.13.1 on a fresh, temporary Docker database with the WildFly and H2 images above. The test uses a fixed registration email, so it requires a database where that email is available.

The Playwright report preserves test results and timings. Local checkout paths were made repository-relative and the absolute Node.js executable path was replaced with `node`. The Maven result contains the test and build summary from the original output. [Previous facade stage report](../../legacy-facade/20261118-173050/report.md)

With JDK 21, run the Spring integration tests from the repository root.

```sh
mvn -B -Pmodern -pl .,modules/new-kitchensink-boot -am clean verify
```

To replay both Playwright projects, forward the WildFly pod in the namespace under test and set `KS_BASE` to its `/kitchensink/` URL. [Deployment guide](../../../deploy/README.md) · [Playwright instructions](../../../e2e/README.md)

```sh
KS_BASE=http://127.0.0.1:18201/kitchensink/ npm --prefix e2e test
```
