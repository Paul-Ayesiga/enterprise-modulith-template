# Event Catalog

Domain events are records in each module's API package, registered on aggregates
(`AggregateRoot.registerEvent`) and published by Spring Data on `save(..)`. Delivery is
**at-least-once** through the Modulith DB-backed registry (`event_publication` table): incomplete
publications are re-published on restart, completed ones are purged by the scheduler after
`app.scheduler.event-retention` (default P7D).

Consumers use `@ApplicationModuleListener` (async, own transaction, after the publisher commits).
Listeners with side effects must be idempotent — call
`EventInbox.recordIfNew(listenerId, messageId)` first and skip when it returns false; derive the
message id from business identity — among the live ones:
`"flag:" + key + ":" + enabled + "@" + occurredAt` (`FeatureFlagChangeNotifier`) and
`subscriptionId + ":" + eventType + ":" + orgId + "@" + occurredAt` (`WebhookDispatcher`).

| Event | Module | Payload | Published when |
|---|---|---|---|
| `ug.co.smsone.settings.SettingChanged` | settings | `key, value` | a setting is created or updated |
| `ug.co.smsone.settings.FeatureFlagChanged` | settings | `key, enabled, occurredAt` | a feature flag is created or toggled |
| `ug.co.smsone.localization.TranslationChanged` | localization | `locale, key, occurredAt` | a translation is created, replaced or deleted (deletes publish explicitly) |
| `ug.co.smsone.identity.UserProvisioned` | identity | `subject, email, occurredAt` | an admin provisions a new local user (`INVITED`) |
| `ug.co.smsone.identity.UserActivated` | identity | `subject, occurredAt` | an `INVITED` user is lazily activated on first access |
| `ug.co.smsone.organization.OrganizationRegistered` | organization | `orgId, alias, occurredAt` | an organization projection is created |
| `ug.co.smsone.organization.MembershipCreated` | organization | `orgId, subject, roleCode, occurredAt` | a user is added to an organization |
| `ug.co.smsone.organization.MembershipRoleChanged` | organization | `orgId, subject, occurredAt` | a member's role is reassigned |
| `ug.co.smsone.organization.MemberRemoved` | organization | `orgId, subject, occurredAt` | a member is removed (published explicitly — a delete doesn't trigger `@DomainEvents`) |
| `ug.co.smsone.organization.RolePermissionsChanged` | organization | `orgId, roleId, occurredAt` | a role's permissions are replaced (custom-role edit, system-role catalog reconciliation) — or the role is soft-deleted, since its grants vanish with it |
| `ug.co.smsone.document.DocumentRegistered` | document | `documentId, orgId, name, source, occurredAt` | a document joins the catalog — upload or a platform producer; published explicitly (the id is persist-assigned) |
| `ug.co.smsone.organization.OrganizationStatusChanged` | organization | `orgId, status, occurredAt` | an organization is suspended or reactivated |
| `ug.co.smsone.organization.OrganizationDeleted` | organization | `orgId, occurredAt` | the platform deletes a tenant (soft; published explicitly — a delete fires no `@DomainEvents`) |
| `ug.co.smsone.subscription.SubscriptionChanged` | subscription | `orgId, planCode, status, occurredAt` | a tenant's plan is assigned or changed |
| `ug.co.smsone.exchange.JobCompleted` | exchange | `jobId, orgId, requester, handler, jobType, outcome, processed, failed, occurredAt` | an exchange job reaches a terminal state — published explicitly from the worker's terminal read (the job row stays authoritative; a crash between the write and the publish loses only the event) |

Every event carries `occurredAt` except `SettingChanged`, which predates the rule and has no consumer
to be idempotent for. Where it is present, `occurredAt` lets an idempotent consumer dedupe redelivery
of the *same* change while still reacting to a genuine later re-toggle to the same state — add it
before giving `SettingChanged` its first listener.

## Consumers

| Event | Consumed by | Effect (idempotent via `EventInbox`) |
|---|---|---|
| `ug.co.smsone.settings.FeatureFlagChanged` | notification | Notifies administrators (email + in-app) that a flag was toggled |
| `RolePermissionsChanged` / `MembershipRoleChanged` / `MembershipCreated` / `MemberRemoved` / `OrganizationStatusChanged` | organization | Evicts the `org-permissions` cache so a role/membership/org-status change takes effect promptly (coarse clear-all) |
| `MembershipCreated` / `MemberRemoved` / `MembershipRoleChanged` / `RolePermissionsChanged` / `OrganizationStatusChanged` | webhooks | Fans the org event out to matching active subscriptions and enqueues a signed delivery each (idempotent via `EventInbox`) |
| `OrganizationRegistered` | search | Indexes the organization (alias) into the org-scoped search projection |
| `UserProvisioned` | search | Indexes the user (email) platform-wide — visible to admin search only |
| `OrganizationDeleted` | organization (evictor), webhooks | Clears the permission cache; fans out `org.deleted` — the tenant's last outbound event |
| `SubscriptionChanged` | subscription (evictor), webhooks | Evicts the `org-entitlements` cache so a plan change bites the next gate; fans out `org.subscription_changed` |
| `JobCompleted` | notification, webhooks | Tells the REQUESTER in-app that their job finished (idempotent, `exchange-job:<id>@<occurredAt>`); fans out `org.exchange.job_completed` with outcome + counters |

The webhooks consumer maps each organization event to its outbound wire code
(`webhooks.internal.WebhookEventType`): `MembershipCreated` → `org.member.added`, `MemberRemoved` →
`org.member.removed`, `MembershipRoleChanged` → `org.member.role_changed`,
`RolePermissionsChanged` → `org.role.permissions_changed`, `OrganizationStatusChanged` →
`org.status_changed`, `OrganizationDeleted` → `org.deleted`, `SubscriptionChanged` →
`org.subscription_changed`, `JobCompleted` → `org.exchange.job_completed`. The full subscribable
vocabulary is on the wire at `GET /api/v1/webhooks/event-types`.

Four events currently have **no consumer**: `SettingChanged`, `UserActivated`,
`TranslationChanged` and `DocumentRegistered`. They are still published through the registry (and appear in the generated
module canvases); a first consumer must follow the `EventInbox` idempotency rule above.

_(The **audit** module does not consume events — it records synchronously via the shared `AuditLog` port at each mutation, so it captures the actor and before/after state the events don't carry.)_

_(**Impersonation adds no event, deliberately.** `platform.impersonation_started` / `_ended` /
`_superseded` are `audit_log.action` values written through the same port, not records in an API
package: nothing outside `identity` reacts to a session opening, so `ImpersonationSession` is a
`BaseEntity` rather than an `AggregateRoot` and produces no `event_publication` rows. There is no
`_expired` action either — expiry is evaluated on read. See `AGENTS.md` §5.5.)_

_(**Exchange publishes exactly one event, from the row.** `JobCompleted` repeats what the fenced
terminal write said — the two can never disagree — and the REST surface never depends on it: the
job row stays the authoritative, pollable truth.)_

The generated per-module canvases in [modulith/](modulith/) list published/listened events per
module and are refreshed on every build — treat this file as the narrative companion, and add a row
whenever a new event type lands.
