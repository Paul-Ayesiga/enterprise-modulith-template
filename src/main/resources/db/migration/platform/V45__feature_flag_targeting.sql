-- Flag targeting: a percentage rollout on the flag itself (bucketed deterministically by org, only
-- meaningful while the flag is enabled) and hard per-org overrides that beat everything.
--
-- THE BUCKETING INPUT IS organization.id, and pinning that in writing is the whole point of this
-- paragraph. The bucket is a hash of (flag_key, org id), which is what makes "10% of orgs" mean the
-- SAME 10% on every request and every instance. That stickiness is a property of the VALUE, not of
-- the column — so when the tenant key moved from the Keycloak organization id to organization.id
-- (V11), every org re-bucketed and any half-finished rollout flipped for a fraction of tenants.
-- Pre-production that costs nothing, which is exactly why it is recorded rather than left implicit:
-- the input must never change again as a SIDE EFFECT of something else. A rollout that has to survive
-- a future re-key needs its own bucket_key column, not whichever identifier happens to be the tenant
-- key that week.
--
-- SPLIT (ADR 0010 §4.1): this is the platform half of V45. Its sibling is db/migration/tenant/V45__feature_flag_targeting.sql.

alter table feature_flag add column percentage int;
