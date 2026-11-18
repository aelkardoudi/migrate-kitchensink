# Migrate Kitchensink

The legacy baseline `8.0.0.GA` runs the Jakarta EE application and its JSF frontend on WildFly 28.0.1.Final, with H2 2.1.214 on Kubernetes. Use the Git tag `baseline/8.0.0.GA` to compare later migration steps against this version.

```text
modules/kitchensink/          Jakarta EE WAR, JSF frontend and Java tests
modules/new-kitchensink-core/  Java 21 member read use cases
modules/new-kitchensink-boot/  Spring Boot member API and H2 adapter
deploy/                        Docker image builds and kind deployment
e2e/                           Playwright REST contract and browser tests
reports/                       Qualification reports and build evidence
```

## Build and run locally

Install JDK 21, Maven, Docker with BuildKit, kind, kubectl and OpenSSL. From the project root, build the WildFly WAR, Spring Boot JAR and their images.

```sh
mvn -Pmodern clean package
./deploy/build-images.sh
./deploy/modern.sh build
```

Create the local cluster with `kind create cluster --name kitchensink` if it does not already exist. Start both deployments in the same namespace and keep port forwarding active.

```sh
./deploy/demo.sh up
./deploy/modern.sh up
./deploy/demo.sh forward
```

Open the [JSF frontend](http://127.0.0.1:18201/kitchensink/) or the [member API](http://127.0.0.1:18201/kitchensink/rest/members). Run `./deploy/demo.sh status` to see the pods. Stop Spring with `./deploy/modern.sh stop`, then stop WildFly and H2 with `./deploy/demo.sh stop`. See the [deployment guide](deploy/README.md) for ports and namespaces.

In IntelliJ IDEA, import the root `pom.xml` and run **Kitchensink - Kubernetes**. View logs and pods in **Services → kind-kitchensink → Workloads → Pod** with the `migrate-kitchensink-dev` namespace selected.

## Spring Boot member reads

Activate the Maven `modern` profile with JDK 21 to build and test the new modules.

```sh
mvn -Pmodern -pl .,modules/new-kitchensink-boot -am clean verify
```

The [Spring Boot module](modules/new-kitchensink-boot/README.md) serves member list and detail requests from the existing H2 database. It runs in its own Kubernetes Deployment. See the [deployment guide](deploy/README.md#deploy-the-spring-member-reads). In IntelliJ IDEA, activate `modern` in the Maven tool window to load both new modules.

WildFly delegates the member list and detail to Spring Boot and handles registration. Redeploy the [previous WildFly image](deploy/README.md#roll-back-delegated-reads) to restore direct H2 reads.

## Run the tests

With Node.js 22.18 or newer, install the pinned dependencies and Chromium once. The test command deploys WildFly, Spring Boot and H2 to `migrate-kitchensink-test` and uses port `18202` for the duration of the run.

```sh
npm --prefix e2e ci
npm --prefix e2e exec -- playwright install chromium
npm --prefix e2e test
```

Use `npm --prefix e2e run test:contrat` for REST only or `npm --prefix e2e run test:e2e` for the browser only. The JSON report is written to `.run/playwright/playwright.json`. See the [Playwright guide](e2e/README.md) and the [Java integration test guide](modules/kitchensink/README.adoc#run_the_arquillian_integration_tests_with_server_distribution).

## Baseline evidence

The functional qualification passed **13 tests on a fresh kind deployment** (1 Maven integration test, 11 REST contract tests and 1 JSF browser test). Performance testing was deferred. The [qualification report](reports/legacy-8.0.0.GA/20261118-163145/rapport.md) links to the results, the SHA-256 of the tested WAR and the evidence manifests.

## Delegated reading evidence

The [non-regression report](reports/delegate-reading/20261118-210256/report.md) documents the REST, JSF and Java integration tests for `migration/03-delegate-reading`. It includes Spring request logs captured while the Playwright suite ran against WildFly.
