import { test, expect } from '@playwright/test';

/**
 * The one invariant both portals must never lose: NOTHING renders without a Keycloak login. An
 * unauthenticated hit on any page must land on the Keycloak login form (edge middleware redirect,
 * OIDC + PKCE) — not on portal content, not on a blank page, not on an error.
 */
test('unauthenticated visit is gated by the Keycloak login form', async ({ page }) => {
  await page.goto('/');

  // The middleware bounces through the OIDC authorize endpoint to the realm's login page.
  await page.waitForURL(/\/realms\/.+\/protocol\/openid-connect\/auth|\/realms\/.+\/login-actions\//, {
    timeout: 15_000,
  });

  // The smsone theme's login form: username + password inputs and the branded page.
  await expect(page.locator('#username')).toBeVisible();
  await expect(page.locator('#password')).toBeVisible();
});

test('deep links are gated too', async ({ page }) => {
  // A bookmarked inner page must not leak a shell of content before the redirect.
  await page.goto('/usage');
  await page.waitForURL(/\/realms\/.+\/protocol\/openid-connect\/auth/, { timeout: 15_000 });
  await expect(page.locator('#username')).toBeVisible();
});
