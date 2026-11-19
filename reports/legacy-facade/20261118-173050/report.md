# Legacy member facades test report

**13 passed, 0 failed, 0 skipped.** Run on 18 November 2026 at 17:30 CET against a fresh local WildFly 28.0.1.Final instance with H2 2.1.214 in memory. Maven 3.9.9 built the WAR with JDK 17. Playwright 1.62.1 ran the REST and Chromium tests against the same WAR.

- Tested Java commit `f2512df18f723588b78cf9d69ab296abc2f96963`
- Tested WAR SHA-256 `02bbad68b5352b5bd3686bfedf5c581e7db33d8ba90e59dce6099865fd0873e2`

| Suite | Result | Evidence |
| --- | --- | --- |
| Maven Failsafe integration | 1/1. REST registration accepts valid input and returns an empty HTTP 200 response. | [Test output](maven-it.txt) · [Failsafe summary](failsafe-summary.xml) |
| Playwright REST contract | 11/11. Covers sorted listing, homonyms, member details, missing IDs, validation, duplicate email and concurrent registrations. | [Playwright results](playwright.json) |
| Playwright Chromium E2E | 1/1. Covers form validation, registration, immediate listing, opening the member detail and duplicate handling. | [Playwright results](playwright.json) |

The concurrent registration test observed one HTTP 200 and one HTTP 400 for two requests with the same email. It verified that exactly one member was persisted. The Playwright evidence uses project-relative file paths and retains only the version in its configuration section. It preserves the test results and attachments.
