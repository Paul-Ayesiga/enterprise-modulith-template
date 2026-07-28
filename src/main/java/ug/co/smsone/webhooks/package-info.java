/**
 * Webhooks module: per-org outbound event subscriptions. Tenants register an endpoint and the events
 * they want; when a matching organization event fires, an {@code @ApplicationModuleListener} fans it
 * out to every active subscription and enqueues a durable delivery. A background worker signs each
 * payload (HMAC-SHA256), POSTs it through the shared SSRF guard, and retries with backoff /
 * dead-letters — the same durable-delivery pattern the notification module uses. Internals under
 * {@code internal}; org-scoped REST gated by the {@code webhook:manage} permission.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Webhooks")
package ug.co.smsone.webhooks;
