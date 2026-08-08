-- A person's registered devices: what they sign in from, and whether the org trusts it. push_token
-- is nullable and forward-looking (a future notification PUSH channel reads it). A device is a
-- soft-deletable aggregate — "revoke this device" keeps the row as the trail. last_seen_at is
-- stamped throttled off the request path.
--
-- person_id is a SOFT ref with no FK: person (V10) is the identity module and this table is profile.
--
-- KNOWN MODELLING BUG, recorded here and fixed in its own slice, NOT by this rename: the header says
-- "whether the ORG trusts it" and there is no org column, while org_security_policy.require_trusted
-- _device is per-org — so a device trusted inside org A satisfies org B's rule for the same person.
-- Fixing it means deciding whether trust belongs on a (person, organization, device) triple, which
-- deserves its own argument rather than riding along inside a column rename.

create table user_device
(
    id           uuid         not null primary key,
    person_id    uuid         not null,
    name         varchar(100) not null,
    kind         varchar(10)  not null,               -- BROWSER | MOBILE | CLI
    fingerprint  varchar(100) not null,               -- opaque client-supplied id (the X-Device-Id)
    push_token   varchar(300),
    trusted      boolean      not null default false,
    last_seen_at timestamptz,
    version      bigint       not null,
    created_at   timestamptz  not null,
    created_by   uuid        ,
    updated_at   timestamptz,
    updated_by   uuid        ,
    deleted_at   timestamptz
);

-- One live registration per (person, fingerprint) — re-registering the same device updates it.
create unique index uq_user_device_person_fingerprint_live on user_device (person_id, fingerprint)
    where deleted_at is null;

create index idx_user_device_person on user_device (person_id, created_at desc, id desc)
    where deleted_at is null;

create index idx_user_device_deleted on user_device (deleted_at)
    where deleted_at is not null;
