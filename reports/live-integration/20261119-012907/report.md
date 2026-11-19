# Live integration test report

**22 tests passed** on November 19, 2026. The Kubernetes run used freshly rebuilt images and the isolated `migrate-kitchensink-verify-live-afpnfu` namespace on `kind-kitchensink`. Playwright started at 01:29:07 CET.

| Suite | Result | Evidence |
| --- | --- | --- |
| Spring Boot integration | 8/8 for the member API and H2 persistence | [Java test output](java-tests.txt) |
| Camel route | 1/1 for acknowledgment and delayed retry | [Java test output](java-tests.txt) |
| Legacy Java integration | 1/1 for REST registration, run with JUnitCore | [Java test output](java-tests.txt) |
| Playwright REST and JSF | 11/11 REST contract tests and 1/1 Chromium registration scenario | [Playwright report](playwright.json) |

Liquibase created the unique member email index before Camel started polling. The eight successful Playwright registrations matched field for field between H2 and MongoDB. The legacy JUnit registration also produced exactly one matching MongoDB document. [Liquibase and Camel startup log](liquibase-camel.log) · [Replication evidence and image references](replication.json)

With Camel stopped, a registration committed to H2 and remained in the outbox. Restarting Camel delivered the member and emptied the outbox. Replaying the same event kept a single unchanged MongoDB document. The final state contained ten new members in both databases and zero pending events. The historical seed member remained in H2.

The legacy profile initially needed dependencies outside the local Maven cache. The existing, compiled `RemoteMemberRegistrationIT` ran directly through JUnitCore against WildFly. The Playwright JSON preserves the original test outcomes and timings, with repository-relative paths.

See the [deployment guide](../../../deploy/README.md#deploy-mongodb-and-the-camel-relay) and [Playwright instructions](../../../e2e/README.md) to reproduce the functional checks.
