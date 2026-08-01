/**
 * Message localization: the translation catalog and its resolution. The module owns which
 * translations exist and how a key resolves for a locale (exact tag → language → default locale →
 * the key itself — a missing translation renders, it never throws); it deliberately does not own
 * which user-visible strings exist — callers bring keys, and a key nobody translated is still a
 * working key. Bundles are cached per locale in the two-level cache and evicted on every write.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Localization")
package ug.co.smsone.localization;
