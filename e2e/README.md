# Playwright tests

The suite in `tests/contrat/` verifies the members REST API. The suite in `tests/e2e/` covers member registration through the JSF frontend in Chromium.

From the repository root, use Node.js 22.18 or newer to install the pinned dependencies and Chromium.

```sh
npm --prefix e2e ci
npm --prefix e2e exec -- playwright install chromium
```

Prepare the kind cluster and images using the [deployment guide](../deploy/README.md), then run both suites.

```sh
npm --prefix e2e test
```

Playwright starts WildFly and H2 in `migrate-kitchensink-test`, forwards local port `18202` for the duration of the run, and writes the report to `.run/playwright/playwright.json`. The test namespace and its volume remain available for later runs. Each registration uses a unique synthetic email address.

Run `npm --prefix e2e run test:contrat` for the REST suite or `npm --prefix e2e run test:e2e` for the browser suite. Set `KS_BASE` to run against an existing test deployment, for example `KS_BASE=http://127.0.0.1:18203/kitchensink/ npm --prefix e2e test`.
