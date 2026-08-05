# Platform Expansion Plan

> The strategy for the next ten workstreams (user-set scope, 2026-08-01): device management ·
> security policies · user groups · API keys · user preferences · avatars · linked accounts ·
> user profiles · contacts · organization switching · integration hub · compliance ·
> maintenance · customer support. Every slice follows AGENTS.md §2.4's recipe (package-info →
> migration → API package → internal → tests → docs sweep → full-suite gate → commit) and serves
> **both axes where applicable**: the tenant manages its own, the platform observes and governs.
> Migration numbering from here: **V28 profile, V29 api-keys, V30 groups, V31 devices,
> V32 security-policies, V33 integration-hub, V34 compliance, V35 maintenance, V36+ support.**

## Sequencing rationale

Dependency-first, then blast-radius: identity enrichment (P1) unblocks support/compliance UX;
API keys (P2) before security policies (P4) because policies want "require key" as an option;
groups (P3) extends RBAC while it is fresh; the integration hub (P5) generalizes the channel
SPI the notification module already proved; compliance (P6) hooks machinery that already exists
(soft-delete purge, consent joins the audit voice); maintenance (P7) is small and gates on
nothing; support (P8) is the largest single module and consumes profiles, groups and SLAs from
maintenance windows — it goes last so it lands on finished ground.

## P1 — `profile`: user profiles, preferences, avatars, contacts, linked accounts, org switching (V28)

- **Model.** `user_profile` (soft-deletable #13): `subject` unique-live, `display_name`, `phone`,
  `timezone`, `locale`, `avatar_key` (files-port key, `avatar/u/<subject>/…`). `user_preference`:
  PK `(subject, pref_key)`, `pref_value varchar(500)` — plain rows, the idempotency-key species.
  Contacts as an `@ElementCollection` on the profile (`user_contact`: kind EMAIL/PHONE/OTHER,
  value, label, primary flag) — the `role_permission` pattern, lifecycle follows the profile.
- **REST (self-service).** `GET/PUT /api/v1/me/profile`; `GET/PUT /api/v1/me/preferences`
  (bulk map put, additive); `PUT /api/v1/me/avatar` (multipart, files port, old key deleted after
  the new one lands) / `GET` (302 presigned) / `DELETE`; contacts inside the profile document.
  **Org switching:** `GET /api/v1/me/organizations` — every ACTIVE membership (org id, alias,
  name, role code) so a dual member's client can offer the switch; the SWITCH itself is a token
  re-request scoped to the chosen org (Keycloak `organization` claim — documented, not an
  endpoint: the server never trusts a client-asserted active org).
- **Linked accounts.** Read-only projection of Keycloak federated identities
  (`GET /api/v1/me/linked-accounts` via the existing admin gateway); linking/unlinking stays a
  Keycloak account-console act — we display, we do not mutate IdP identity.
- **Platform axis.** `GET /api/v1/admin/users/{subject}/profile` (support) — the support view
  that tickets (P8) will embed.
- **Gate.** Profile round-trip incl. avatar 302 + old-key cleanup; preference upsert; contact
  validation; `/me/organizations` for a dual member; ProfileApiTest.

## P2 — `apikeys`: machine credentials (V29)

- **Model.** `api_key` (soft-deletable): `org_id` null = platform key; `name`, `prefix`
  (`sk_live_<8>`, shown always), `secret_hash` (SHA-256 — HASHED, never encrypted: unlike webhook
  secrets we never need the plaintext back, we only verify), `permissions text` (comma-joined
  subset of the org catalog; platform keys carry a platform tier instead), `expires_at`,
  `last_used_at` (throttled write, ≤1/min).
- **Auth.** `ApiKeyAuthenticationFilter` before the bearer path: `X-Api-Key: <prefix>.<secret>` →
  hash-compare → a synthetic principal whose org permissions are the key's subset ∩ nothing more
  (a key can never out-rank its creator at mint time — the escalation guard's rule applied to
  machines). Keys never pass the platform-role gates unless minted as platform keys by
  `platform-admin`.
- **REST.** Org: `POST /api/v1/orgs/{orgId}/api-keys` (secret shown ONCE — the webhook-secret
  idiom), `GET` list (masked), `DELETE` (revoke, immediate). Platform: same under
  `/api/v1/admin/api-keys`. All audited; `smsone.apikeys.used{key}` counter.
- **Gate.** Mint→call-with-key→403-beyond-subset→revoke→401; expiry honored; ApiKeyAuthTest.

## P3 — `groups`: org user groups (V30)

- **Model.** `org_group` (soft-deletable): org-scoped, name, one role (a group IS a named
  assignment funnel — members inherit the group's role in ADDITION to their direct role; the
  permission resolver unions them). `group_member` element rows (subject).
- **Rules.** Creating/re-roling a group passes the escalation guard (granting via a group is
  still granting); the permission-cache evictor listens to group events.
- **REST.** `/api/v1/orgs/{orgId}/groups` CRUD + members add/remove (`member:role:assign`
  gates); admin read `/api/v1/admin/orgs/{id}/groups`.
- **Gate.** Union resolution proven (direct MEMBER + group ADMIN ⇒ admin perms); escalation
  refused; eviction on group change; GroupRbacTest.

## P4 — `devices` + `security-policies` (V31, V32)

- **Devices.** `user_device` (soft-deletable): subject, name, kind (BROWSER/MOBILE/CLI),
  fingerprint hash, push_token (nullable — the notification module's future PUSH channel reads
  it), `last_seen_at`, `trusted` flag. Self-service CRUD under `/api/v1/me/devices` +
  revoke-all; a lightweight filter stamps `last_seen_at` (throttled) from an `X-Device-Id`
  header when present. Platform: support reads a user's devices (ticket context).
- **Security policies.** `org_security_policy` (one row per org, soft-deletable): `ip_allowlist`
  (CIDRs — enforced in a filter after auth for org-scoped calls), `require_api_key_for_writes`,
  `session_max_age` (advisory claim check: tokens older than this are refused for the org),
  `enforce_trusted_devices` (org calls require a trusted registered device id). Platform
  defaults under `app.security-policy.*`; org overrides tighten, never loosen. Every policy
  DENY is a counted, audited 403 naming the policy — a policy 403 must never look like RBAC.
- **Gate.** Allowlisted CIDR passes/foreign denies; stale-token refusal; trusted-device gate;
  policy 403s carry the policy name; SecurityPolicyTest, DeviceApiTest.

## P5 — `integrationhub`: provider configs (V33)

- **Model.** `integration` (soft-deletable): scope (org id null = platform default), kind
  (SMS_PROVIDER / EMAIL_PROVIDER / PAYMENT_GATEWAY), provider code, config jsonb-free —
  `integration_setting` element rows (key, value, `secret` flag → values AES-GCM encrypted with
  the webhook `SecretCipher` pattern, masked on read), `enabled`, priority.
- **Resolution.** `Integrations` port: `resolve(orgId, kind)` → org's enabled integration else
  the platform default — consumed by the notification channel senders (per-org SMTP/SMS creds at
  send time) and by billing (payment gateway selection is Kill Bill plugin config; the hub row
  records WHICH gateway an org uses so the platform can answer it). Extending = new provider
  code + a sender that reads its config; the SPI stays closed.
- **REST.** Org `/api/v1/orgs/{orgId}/integrations` CRUD (`org:update`), platform defaults under
  `/api/v1/admin/integrations`; test-connection endpoint per integration (fires a no-op probe
  through the sender).
- **Gate.** Org SMTP override actually used by a delivery (receiver asserts the org's host);
  secrets masked + encrypted at rest; fallback to platform default; IntegrationHubTest.

## P6 — `compliance`: GDPR, consent, retention, legal holds (V34)

- **Consent.** `consent_record` (append-only, the audit species — NEVER soft-deletable):
  subject, purpose code, granted/withdrawn, occurredAt, source. `GET/POST /api/v1/me/consents`;
  purpose catalog seeded (`marketing`, `analytics`, …). Withdrawal is a new row, not an update.
- **Data retention.** `org_retention_policy`: per-org overrides for the retention windows the
  platform already enforces (audit visibility, exchange jobs, webhook deliveries) — the purge
  jobs consult the org override where one exists, floor-bounded by platform minimums.
- **Erasure (GDPR art. 17).** `erasure_request`: subject, requestedBy, status
  (RECEIVED→VERIFYING→EXECUTED/REFUSED). Execution = the machinery that already exists: soft
  delete the user projection + profile, purge accelerates past retention for that subject
  (documented SQL path per table), search residue sweep already runs. Every step audited.
- **Legal holds.** `legal_hold` (append-only-ish: released, never deleted): scope (org or
  subject), reason, placedBy. **The purge jobs check holds** — a held row is skipped and
  counted (`smsone.purge.held`); an erasure request against a held subject parks as REFUSED
  with the hold named. This is the one feature that changes existing jobs; it lands with tests
  on every purge path.
- **Privacy.** `GET /api/v1/me/data-export` — a self-service exchange EXPORT of the caller's own
  data (profile, memberships, consents) reusing the exchange platform end to end.
- **Gate.** Hold blocks purge + erasure; consent append-only; retention override honored with
  platform floor; export round-trips; ComplianceTest.

## P7 — `maintenance` (V35)

- **Model.** `maintenance_window` (soft-deletable): scope (platform or org), starts/ends,
  message, mode (ANNOUNCE = banner metadata only | RESTRICT = org-scoped writes answer 503 with
  Retry-After). Platform CRUD; org reads its applicable windows
  (`GET /api/v1/orgs/{orgId}/maintenance`). Activation/deactivation of ORGS already exists
  (suspend/reactivate + delete — TENANT_LIFECYCLE.md); this adds scheduled, announced windows
  and a platform-wide RESTRICT switch (`app.maintenance.enabled` + row-driven).
- **Gate.** RESTRICT window 503s an org write with Retry-After but reads pass; ANNOUNCE never
  blocks; MaintenanceTest.

## P8 — `support`: tickets, assignments, SLAs, responses, escalations (V36–V37)

- **Model.** `ticket` (soft-deletable): org, opener subject, subject line, body, category,
  priority (P1–P4), status (OPEN→IN_PROGRESS→WAITING_ON_CUSTOMER→RESOLVED→CLOSED), assignee
  (platform-support subject), `sla_due_at`, `first_response_at`. `ticket_message` (append-only
  child, cascade FK): author, body, internal flag (platform-only notes). `sla_policy` seeded per
  priority (first-response + resolution targets); org overrides may come from the subscription
  plan (an ENTERPRISE entitlement — `support.sla` key ties P8 to the existing gating).
- **Flows.** Tenant opens/reads/replies to own org's tickets (`org:read` + opener/any member per
  org policy); platform support lists the queue (filter by status/priority/breach), assigns
  (self or others), replies (public or internal), resolves. **Escalation**: a ShedLock minute
  job flags SLA breaches → priority bump + `smsone.support.breached` counter +
  admin notification + `org.ticket.escalated` webhook. `JobCompleted`'s notifier pattern gives
  openers "you have a reply" in-app mail.
- **REST.** Org: `/api/v1/orgs/{orgId}/tickets[/{id}]` + `/messages`. Platform:
  `/api/v1/admin/tickets` (queue, filters, assignment, internal notes). Events:
  `TicketOpened/TicketEscalated/TicketResolved` → webhooks + notifications.
- **Gate.** Full lifecycle with SLA breach escalation under a fake clock; internal notes never
  reach the tenant; queue filters; SupportFlowTest.

## Standing rules for every slice

Counts to bump each time a soft-deletable joins (AGENTS §4.1, DATA_MODEL §2.2/§5.1,
`AnalyticsReport` javadoc, `PURGE_ORDER`); tag budget ≤8 per group (new controllers need
OpenApiConfig map entries — flag each to the user); both-axes review (§14) before the gate;
`docs/guides/api-guide.html` gains a section per shipped slice.
