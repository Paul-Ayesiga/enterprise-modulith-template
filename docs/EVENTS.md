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
| `ug.co.smsone.settings.FeatureFlagChanged` | settings | `key, enabled` | a feature flag is created or toggled |

## Consumers

| Event | Consumed by | Effect (idempotent via `EventInbox`) |
|---|---|---|
| `ug.co.smsone.settings.FeatureFlagChanged` | notification | Notifies administrators (email + in-app) that a flag was toggled |

The generated per-module canvases in [modulith/](modulith/) list published/listened events per
module and are refreshed on every build — treat this file as the narrative companion, and add a row
whenever a new event type lands.
