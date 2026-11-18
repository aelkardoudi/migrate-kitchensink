# Playwright tests

The suite in `tests/contrat/` verifies the members REST API. The suite in `tests/e2e/` covers member registration through the JSF frontend in Chromium.

From the repository root, use Node.js 22.18 or newer to install the pinned dependencies and Chromium.

```sh
npm --prefix e2e ci
npm --prefix e2e exec -- playwright install chromium
```

Prepare the kind cluster and build the WildFly, Spring Boot and H2 images. [Deployment guide](../deploy/README.md)

```sh
npm --prefix e2e test
```

Playwright starts WildFly and H2 in `migrate-kitchensink-test`, then starts Spring Boot in the same namespace. Both suites reach WildFly on forwarded port `18202`. WildFly delegates the member list and detail to Spring Boot. The report is written to `.run/playwright/playwright.json`. The test namespace and its volume remain available for later runs. Each registration uses a unique synthetic email address.

Run `npm --prefix e2e run test:contrat` for the REST suite or `npm --prefix e2e run test:e2e` for the browser suite. To use an existing deployment, forward its WildFly service and set `KS_BASE` to that URL.

```sh
KS_BASE=http://127.0.0.1:18201/kitchensink/ npm --prefix e2e test
```
