-- Device trust becomes per-organization. This is the slice V31's header promised:
--
--   "KNOWN MODELLING BUG, recorded here and fixed in its own slice, NOT by this rename: the header
--    says 'whether the ORG trusts it' and there is no org column, while
--    org_security_policy.require_trusted_device is per-org — so a device trusted inside org A
--    satisfies org B's rule for the same person."
--
-- It was worse in practice than that note implies, because nothing constrained WHO could grant it.
-- SecurityPolicyService.setDeviceTrust took an orgId, used it only for the audit line, and resolved the
-- device by (deviceId, personId) with no tenant predicate at all — so an org:update holder in any org
-- could flip the flag on any person's device platform-wide, and the flag then satisfied every org's
-- policy. Self-signup is open (SecurityConfig permits /api/v1/signup) and a new org's creator is OWNER
-- with every permission, so the cheapest attack needed no guessed identifiers whatsoever: make your own
-- org, bless your own device with your own ids, and walk through a different tenant's
-- require_trusted_device — a control that tenant deliberately turned on.
--
-- THE SHAPE. Trust is a grant by an organization over a device: (device_id, org_id). Not the
-- (person, organization, device) triple V31 wondered about, because user_device.person_id already fixes
-- the person — carrying it here as well would let the two disagree, and there is no third thing the
-- triple could express that this pair cannot. (V53 reverses that last clause: once the FK to
-- user_device is gone, person_id and fingerprint have to be carried here, and the reason they can no
-- longer disagree is a revocation listener rather than a join. Read V53 before touching this table.)
--
-- Absence of a row is the absence of trust, so revoking is a delete and no row means "not trusted" for
-- an org that never granted it. That is the direction a security default should fail in; a boolean
-- column defaults to a value, a missing row cannot.
--
-- NO BACKFILL, deliberately. Pre-production, so there is nothing to migrate — and even if there were,
-- projecting each trusted device onto every org its person belongs to would recreate exactly the
-- cross-tenant grant this migration exists to remove. Trust is re-granted per org, by that org.
--
-- SPLIT (ADR 0010 §4.1): this is the tenant half of V51. Its sibling is db/migration/platform/V51__device_trust_per_org.sql.

create table user_device_trust
(
    device_id            uuid        not null,        -- user_device.id — SOFT ref, no FK: platform tier
    org_id               uuid        not null,        -- organization.id; soft ref, no cross-module FK
    granted_at           timestamptz not null,
    granted_by_person_id uuid,                        -- person.id; soft ref, nullable for system grants
    primary key (device_id, org_id)
);

-- device_id WAS `references user_device (id) on delete cascade`, and that cascade was doing security
-- work — a hard-deleted device could not leave its grants behind. V53 replaced it with three
-- mechanisms because `user_device` is platform-tier (a device belongs to a human) while a grant is an
-- organization's; the split moves the cut here, since `user_device` is not on this sequence's
-- search_path and after Phase 7 not in this database. READ V53's tenant half before assuming a
-- deleted device leaves no grant behind: nothing in this table can see `user_device.deleted_at` any
-- more, so the row's EXISTENCE has to mean the device is live, maintained by an event-driven delete
-- plus SoftDeletePurgeJob's reconciler. org_id is a soft ref for the usual reason: this modulith is
-- destined to split, and organization lives in another module.
--
-- The primary key (device_id, org_id) already serves the enforcement lookup, which arrives with a
-- device and asks about one org. The extra index is for the reverse question — "what does this org
-- trust" — which the admin surface asks, and which the PK cannot answer without a full scan.
create index idx_user_device_trust_org on user_device_trust (org_id);
