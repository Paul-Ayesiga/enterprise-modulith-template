# Event Catalog

Domain events are records in each module's API package, registered on aggregates
(`AggregateRoot.registerEvent`) and published by Spring Data on `save(..)`. Delivery is
**at-least-once** through the Modulith DB-backed registry (`event_publication` table): incomplete
publications are re-published on restart, completed ones are purged by the scheduler after
`app.scheduler.event-retention` (default P7D).

Consumers use `@ApplicationModuleListener` (async, own transaction, after the publisher commits).
Listeners with side effects must be idempotent — call
`EventInbox.recordIfNew(listenerId, messageId)` first and skip when it returns false; derive the
message id from business identity (e.g. `"setting:" + key + ":" + version`).

| Event | Module | Payload | Published when |
|---|---|---|---|
| `ug.co.smsone.settings.SettingChanged` | settings | `key, value` | a setting is created or updated |
| `ug.co.smsone.settings.FeatureFlagChanged` | settings | `key, enabled, occurredAt` | a feature flag is created or toggled |
| `ug.co.smsone.identity.UserProvisioned` | identity | `subject, email, occurredAt` | an admin provisions a new local user (`INVITED`) |
| `ug.co.smsone.identity.UserActivated` | identity | `subject, occurredAt` | an `INVITED` user is lazily activated on first access |
| `ug.co.smsone.organization.OrganizationRegistered` | organization | `orgId, alias, occurredAt` | an organization projection is created |
| `ug.co.smsone.organization.MembershipCreated` | organization | `orgId, subject, roleCode, occurredAt` | a user is added to an organization |
| `ug.co.smsone.organization.MembershipRoleChanged` | organization | `orgId, subject, occurredAt` | a member's role is reassigned |
| `ug.co.smsone.organization.MemberRemoved` | organization | `orgId, subject, occurredAt` | a member is removed (published explicitly — a delete doesn't trigger `@DomainEvents`) |
| `ug.co.smsone.organization.RolePermissionsChanged` | organization | `orgId, roleId, occurredAt` | a role's permissions are replaced (custom-role edit, or system-role catalog reconciliation) |
| `ug.co.smsone.organization.OrganizationStatusChanged` | organization | `orgId, status, occurredAt` | an organization is suspended or reactivated |

`occurredAt` lets idempotent consumers dedupe redelivery of the *same* change while still reacting to a genuine later re-toggle to the same state.

## Consumers

| Event | Consumed by | Effect (idempotent via `EventInbox`) |
|---|---|---|
| `ug.co.smsone.settings.FeatureFlagChanged` | notification | Notifies administrators (email + in-app) that a flag was toggled |
| `RolePermissionsChanged` / `MembershipRoleChanged` / `MembershipCreated` / `MemberRemoved` / `OrganizationStatusChanged` | organization | Evicts the `org-permissions` cache so a role/membership/org-status change takes effect promptly (coarse clear-all) |

| `MembershipCreated` / `MemberRemoved` / `MembershipRoleChanged` / `RolePermissionsChanged` / `OrganizationStatusChanged` | webhooks | Fans the org event out to matching active subscriptions and enqueues a signed delivery each (idempotent via `EventInbox`) |

_(The **audit** module does not consume events — it records synchronously via the shared `AuditLog` port at each mutation, so it captures the actor and before/after state the events don't carry.)_

The generated per-module canvases in [modulith/](modulith/) list published/listened events per
module and are refreshed on every build — treat this file as the narrative companion, and add a row
whenever a new event type lands.
