# Member backfill qualification

**33 tests passed on November 19, 2026.** The non-regression run used the isolated namespace `migrate-kitchensink-verify-batch-06` on `kind-kitchensink`. Playwright started at 10:57:17 CET.

| Suite | Result | Evidence |
| --- | --- | --- |
| Spring member API | 8/8 | [Maven results](maven-tests.txt) |
| Shared MongoDB writer | 3/3 | [Maven results](maven-tests.txt) |
| Camel relay | 1/1 | [Maven results](maven-tests.txt) |
| Spring Batch | 9/9 | [Maven results](maven-tests.txt) |
| Playwright | 11/11 REST contracts and 1/1 JSF Chromium scenario | [Playwright report](playwright.json) |

The Spring Batch tests cover the inclusive bound, restart from a committed chunk, outbox reconciliation, a changing count during verification, and restart at the whole-table verification step. The isolated Kubernetes run drained the outbox after Playwright. Job `member-backfill-4wpnf` then completed the copy, the bounded verification and the whole-table verification. Both controls reported 18 members in each database. [Kubernetes evidence](kubernetes.log)

In the persistent test namespace, a bounded run with `maxMemberId=0` verified one member in each database and 47 in each database at the whole-table step. A full run verified 47 at both steps. [Kubernetes evidence](kubernetes.log)

The initial historical copy increased MongoDB from 10 to 47 members. A direct comparison by ID verified the canonical fields of all 47 members, including the historical member with ID `0`. The in-job verification steps check counts and explain pending deliveries with outbox IDs. [Initial reconciliation results](reconciliation.json)

Use the [deployment guide](../../../deploy/README.md#run-the-spring-batch-member-backfill) and the [Playwright instructions](../../../e2e/README.md) to reproduce the checks.
