/**
 * Machine credentials: API keys that authenticate a caller without a human token. Implements the
 * shared {@code ApiKeyAuthenticator} port (the filter lives in {@code shared}, the store here). Org
 * keys carry a permission SUBSET capped at mint by what their creator holds — a key can never
 * out-rank its creator, the escalation guard applied to machines. Platform keys carry a support
 * tier (machines read; humans change). Secrets are HASHED (SHA-256): a dump yields nothing usable,
 * and unlike webhook signing secrets we never need the plaintext back — we only verify a presented
 * one. The full secret is shown exactly once, at mint.
 */
@org.springframework.modulith.ApplicationModule(displayName = "API Keys")
package ug.co.smsone.apikeys;
