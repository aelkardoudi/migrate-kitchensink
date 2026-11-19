# Migrate Kitchensink

WildFly serves the JSF frontend and forwards member requests to Spring Boot. Spring Boot reads and writes members in MongoDB and applies its Liquibase changelog at startup.

| Module | Responsibility |
| --- | --- |
| [kitchensink](modules/kitchensink/README.md) | JSF frontend and public REST routes on WildFly |
| [new-kitchensink-core](modules/new-kitchensink-core/README.md) | Member use cases and persistence port |
| [new-kitchensink-boot](modules/new-kitchensink-boot/README.md) | Spring Boot API and MongoDB adapter |
| [kitchensink-mongodb-migrations](modules/kitchensink-mongodb-migrations/README.md) | MongoDB Liquibase changelog |

## Run locally

Install JDK 21, Maven, Docker, kind, kubectl and OpenSSL. Build the application and start the three workloads on the local kind cluster.

```sh
mvn clean verify
./deploy/demo.sh build
./deploy/demo.sh up
./deploy/demo.sh forward
```

Open the [JSF frontend](http://127.0.0.1:18201/kitchensink/index.jsf) or [member list](http://127.0.0.1:18201/kitchensink/rest/members). Use `./deploy/demo.sh status` to inspect the workloads and `./deploy/demo.sh stop` to stop them while preserving the MongoDB volume. See the [deployment guide](deploy/README.md) for IntelliJ and existing namespaces.

## Run the Playwright tests

Use Node.js 22.18 or newer. The suite starts an isolated kind deployment and runs 11 API contract scenarios and one JSF browser scenario.

```sh
npm --prefix e2e ci
npm --prefix e2e exec -- playwright install chromium
npm --prefix e2e test
```

Use [the Playwright guide](e2e/README.md) to test an existing deployment with `KS_BASE` or open the JSON report.

## Migration history

Each tag captures a runnable migration stage and its original deployment. Open the report for the evidence from that stage.

| Tag | Qualification |
| --- | --- |
| `baseline/8.0.0.GA` | [WildFly, JSF and H2 baseline](reports/legacy-8.0.0.GA/20261118-163145/rapport.md) |
| `migration/02-legacy-facade` | [WildFly façades](reports/legacy-facade/20261118-173050/report.md) |
| `migration/03-delegate-reading` | [Delegate reads to Spring Boot](reports/delegate-reading/20261118-210256/report.md) |
| `migration/04-delegate-writing` | [Delegate registration to Spring Boot](reports/delegate-writing/20261118-230305/report.md) |
| `migration/05-live-integration` | [Replicate to MongoDB with Camel](reports/live-integration/20261119-012907/report.md) |
| `migration/06-backfill-batch` | [Copy historical H2 data](reports/member-backfill/20261119-105717/report.md) |
| `migration/07-mongodb-cutover` | [Switch reads and writes to MongoDB](reports/mongodb-cutover/20261119-112528/report.md) |
| `migration/08-final-cleanup` | [Retire migration workloads and qualify the final deployment](reports/final-cleanup/20261119-121054/report.md) |
