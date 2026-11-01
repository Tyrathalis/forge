import { defineConfig } from '@playwright/test';

// Each test gets its own Forge server (server.ts), or a freshly reset one for probes (probe.ts). So three run at once
export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  // zz- files are one-off probes and are not part of the suite. FORGE_E2E_PROBES=1 runs them when named
  testIgnore: process.env.FORGE_E2E_PROBES ? [] : 'zz-*',
  timeout: 240_000,
  expect: { timeout: 20_000 },
  workers: 3,
  fullyParallel: true,
  reporter: [['list']],
  use: {
    headless: true,
    viewport: { width: 1440, height: 900 },
    actionTimeout: 20_000,
  },
});
