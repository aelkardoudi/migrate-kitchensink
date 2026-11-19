# Final cleanup qualification

**20 tests passed on November 19, 2026.** The qualification started at 12:10:54 CET in `migrate-kitchensink-final` on `kind-kitchensink`.

| Suite | Result | Evidence |
| --- | --- | --- |
| Maven | 6 Spring HTTP contract tests and 2 MongoDB adapter tests passed | [Maven results](maven-tests.txt) |
| Playwright on Kubernetes | 11 REST contract scenarios and 1 JSF Chromium scenario passed | [Playwright report](playwright.json) |

## Cold deployment

The new namespace started from an empty MongoDB database in 24 seconds. WildFly, Spring Boot and MongoDB each had one ready pod. Liquibase applied the unique email index and initialized the BSON Int64 member counter at zero. Playwright registered eight members and verified the REST contract and the JSF list refresh after registration. [Kubernetes evidence](kubernetes.log) · [Reconciliation evidence](reconciliation.json)

## Restart and existing data

The qualification replaced the MongoDB and Spring Boot pods. All eight test members matched their pre-restart fields. The MongoDB claim, member counter, unique index and Liquibase history were preserved. The first REST probe during the Spring rollout returned HTTP 503. WildFly logged a connection refusal to Spring. Subsequent probes returned HTTP 200 for the JSF page, member list and member detail. [Kubernetes evidence](kubernetes.log)

In the existing `migrate-kitchensink-test` namespace, upgrading and reconciling the 47 existing members took 52 seconds. Their fields and ID counter matched before and after. The outbox was empty and no H2 writer session remained before Camel and H2 were stopped. The three original volume claims were preserved. A public REST probe still returned all 47 members after the shutdown. [Kubernetes evidence](kubernetes.log) · [Reconciliation evidence](reconciliation.json)

See the [deployment guide](../../../deploy/README.md) and the [Playwright instructions](../../../e2e/README.md) to reproduce the qualification.
