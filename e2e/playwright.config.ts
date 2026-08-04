import { defineConfig } from '@playwright/test';

/**
 * Portal E2E against a RUNNING stack (make up + both portals + the modulith) — this suite starts
 * nothing itself, which is why it is not in the PR path (see docs/PRODUCTION.md §E2E gate).
 * Point it at any environment via env vars; defaults match local dev ports.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'developer-portal',
      use: { baseURL: process.env.PORTAL_URL ?? 'http://localhost:3001' },
    },
    {
      name: 'admin-portal',
      use: { baseURL: process.env.ADMIN_PORTAL_URL ?? 'http://localhost:3002' },
    },
  ],
});
