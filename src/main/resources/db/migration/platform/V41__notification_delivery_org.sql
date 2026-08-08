-- The recipient's organization context, carried from dispatch to the channel sender so per-org
-- integration choices (which SMS provider serves THIS org) resolve at send time. Nullable: platform
-- notifications (admin alerts) have no org and fall through to the platform default / env config.
--
-- org_id is organization.id (V11) — a SOFT ref with no FK, because notification and organization are
-- different modules (AGENTS §1). It held the Keycloak organization id until organization.id became
-- the tenant key. Worth stating, because this column is never read on its own: it is handed to the
-- integration hub (V33) to resolve the org's SMS provider, so it and integration.org_id are only ever
-- compared to EACH OTHER. Retarget one without the other and the lookup simply misses — every org
-- silently falls through to the platform default provider, with no error and no failing constraint.
--
-- The neighbouring `recipient` column (V9) is deliberately NOT retargeted alongside it, and a sweep
-- looking for identity columns will want to. It is polymorphic, discriminated by `channel`: an email
-- address, an E.164 number, a Slack webhook URL. Only channel = 'IN_APP' holds a person, so those
-- values are delivery ADDRESSES in four cases out of five and a person_id would be wrong for all four.

alter table notification_delivery add column org_id uuid;
