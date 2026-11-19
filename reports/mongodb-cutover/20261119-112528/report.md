# MongoDB cutover qualification

**35 tests passed on November 19, 2026.** Playwright started at 11:25:28 CET in the isolated `migrate-kitchensink-verify-batch-06` namespace on `kind-kitchensink`.

| Suite | Result | Evidence |
| --- | --- | --- |
| Spring Boot member API | 8 H2 integration tests and 2 MongoDB adapter tests | [Maven results](maven-tests.txt) |
| Shared MongoDB writer | 3/3 | [Maven results](maven-tests.txt) |
| Camel relay | 1/1 | [Maven results](maven-tests.txt) |
| Spring Batch | 9/9 | [Maven results](maven-tests.txt) |
| Playwright on MongoDB | 11 REST contracts and 1 JSF Chromium scenario | [Playwright report](playwright.json) |

The rehearsal deferred one H2 outbox event. The first cutover attempt reached its drain timeout and restored the H2-backed deployment before releasing MongoDB writes. The next attempt delivered the pending event, verified the 19 members by ID and field, initialized the counter and reopened the frontend after 41 seconds. The H2 deployment command then rejected an attempted redeploy in the switched namespace. [Kubernetes evidence](kubernetes.log)

Playwright created eight documents in MongoDB and preserved the contents of all 19 H2 rows. The new IDs were BSON Int64 values above the H2 maximum. Concurrent requests for the same email returned one HTTP 200 and one HTTP 409. The JSF test showed the new member in the form response. [Reconciliation](reconciliation.json) · [Playwright report](playwright.json)

The cutover in `migrate-kitchensink-test` reopened the frontend after 41 seconds. Both databases held the same 47 members with an empty outbox. Spring Boot was running with the `mongo` profile and the public JSF page and REST listing responded successfully. H2 and Camel were ready. [Kubernetes evidence](kubernetes.log) · [Reconciliation](reconciliation.json)

Use the [cutover guide](../../../deploy/README.md#switch-member-reads-and-writes-to-mongodb) and the [Playwright instructions](../../../e2e/README.md) to reproduce the rehearsal.
