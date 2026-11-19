# Playwright tests

`tests/contrat/` contains 11 member API contract scenarios. `tests/e2e/` checks registration, validation and list refresh through the JSF frontend in Chromium.

From the repository root, install Node.js 22.18 or newer and the pinned dependencies. Build the application images using the [deployment guide](../deploy/README.md).

```sh
npm --prefix e2e ci
npm --prefix e2e exec -- playwright install chromium
npm --prefix e2e test
```

Playwright starts WildFly, Spring Boot and MongoDB in `migrate-kitchensink-final-e2e`, then forwards the public service to port `18202`. The namespace and its volume persist across runs. The JSON report is at `.run/playwright/playwright.json`.

To test another deployment, forward its public service and supply `KS_BASE` with the `/kitchensink/` context path. The suite creates synthetic members in the selected database.

```sh
KS_BASE=http://127.0.0.1:18201/kitchensink/ npm --prefix e2e test
```

Run `npm --prefix e2e run test:contrat` for REST only or `npm --prefix e2e run test:e2e` for the browser only.
