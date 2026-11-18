# Migrate Kitchensink

The legacy baseline `8.0.0.GA` runs the Jakarta EE application and its JSF frontend on WildFly 28.0.1.Final, with H2 2.1.214 on Kubernetes. Use the Git tag `baseline/8.0.0.GA` to compare later migration steps against this version.

```text
modules/kitchensink/  Jakarta EE WAR, JSF frontend and Java tests
deploy/               Docker image builds and kind deployment
e2e/                  Playwright REST contract and browser tests
reports/              Qualification reports and build evidence
```

## Build and run locally

Install JDK 17, Maven, Docker with BuildKit, kind, kubectl and OpenSSL. From the project root, build the WAR and the images.

```sh
mvn clean package
./deploy/build-images.sh
```

Create the local cluster with `kind create cluster --name kitchensink` if it does not already exist. Then start the application and keep port forwarding active.

```sh
./deploy/demo.sh up
./deploy/demo.sh forward
```

Open the [JSF frontend](http://127.0.0.1:18201/kitchensink/) or the [member API](http://127.0.0.1:18201/kitchensink/rest/members). Run `./deploy/demo.sh status` to see the pods or `./deploy/demo.sh stop` to stop WildFly and H2 while keeping the database volume. See the [deployment guide](deploy/README.md) for ports and namespaces.

In IntelliJ IDEA, import the root `pom.xml` and run **Kitchensink - Kubernetes**. View logs and pods in **Services → kind-kitchensink → Workloads → Pod** with the `migrate-kitchensink-dev` namespace selected.

## Run the tests

With Node.js 22.18 or newer, install the pinned dependencies and Chromium once. The test command deploys to `migrate-kitchensink-test` and uses port `18202` for the duration of the run.

```sh
npm --prefix e2e ci
npm --prefix e2e exec -- playwright install chromium
npm --prefix e2e test
```

Use `npm --prefix e2e run test:contrat` for REST only or `npm --prefix e2e run test:e2e` for the browser only. The JSON report is written to `.run/playwright/playwright.json`. See the [Playwright guide](e2e/README.md) and the [Java integration test guide](modules/kitchensink/README.adoc#run_the_arquillian_integration_tests_with_server_distribution).

## Baseline evidence

The functional qualification passed **13 tests on a fresh kind deployment** (1 Maven integration test, 11 REST contract tests and 1 JSF browser test). Performance testing was deferred. The [qualification report](reports/legacy-8.0.0.GA/20261118-163145/rapport.md) links to the results, the SHA-256 of the tested WAR and the evidence manifests.
