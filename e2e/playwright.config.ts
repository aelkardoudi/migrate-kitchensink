import { defineConfig } from '@playwright/test';
import { resolve } from 'node:path';

const localBase = 'http://127.0.0.1:18202/kitchensink/';
const testNamespace = 'migrate-kitchensink-test';
const target = process.env.KS_BASE;
const baseURL = new URL(target ?? localBase);
if (!['http:', 'https:'].includes(baseURL.protocol) || baseURL.username || baseURL.password) {
  throw new Error('KS_BASE must be an HTTP(S) URL without credentials.');
}
baseURL.pathname = baseURL.pathname.replace(/\/+$/, '') + '/';

const reportDir = resolve(import.meta.dirname, '../.run/playwright');

export default defineConfig({
  workers: 1,
  timeout: 40_000,
  reporter: [
    ['list'],
    ['json', { outputFile: resolve(reportDir, 'playwright.json') }],
  ],
  outputDir: resolve(reportDir, 'artifacts'),
  use: {
    baseURL: baseURL.href,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'contrat', testDir: './tests/contrat' },
    { name: 'e2e', testDir: './tests/e2e' },
  ],
  webServer: target === undefined ? {
    command: `../deploy/demo.sh up --namespace ${testNamespace} --port 18202`
      + ` && ../deploy/modern.sh up --namespace ${testNamespace}`
      + ` && exec ../deploy/demo.sh forward --namespace ${testNamespace} --port 18202`,
    url: new URL('index.jsf', localBase).href,
    timeout: 8 * 60_000,
    reuseExistingServer: false,
    stdout: 'pipe',
    stderr: 'pipe',
  } : undefined,
});
