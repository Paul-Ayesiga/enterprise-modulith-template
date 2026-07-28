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
| `ug.co.smsone.organization.RolePermissionsChanged` | organization | `orgId, roleId, occurredAt` | a custom role's permissions are replaced |

`occurredAt` lets idempotent consumers dedupe redelivery of the *same* change while still reacting to a genuine later re-toggle to the same state.

## Consumers

| Event | Consumed by | Effect (idempotent via `EventInbox`) |
|---|---|---|
| `ug.co.smsone.settings.FeatureFlagChanged` | notification | Notifies administrators (email + in-app) that a flag was toggled |
| `RolePermissionsChanged` / `MembershipRoleChanged` / `MemberRemoved` | organization | Evicts the `org-permissions` cache so a role/membership change takes effect promptly (coarse clear-all) |

The generated per-module canvases in [modulith/](modulith/) list published/listened events per
module and are refreshed on every build — treat this file as the narrative companion, and add a row
whenever a new event type lands.
