-- Per-org retention overrides for the ORG-scoped logs (webhook deliveries, exchange jobs). An org
-- (an enterprise data-retention contract, typically) can keep — or drop — its own rows on a
-- different schedule than the platform default. Consulted by the retention jobs through the
-- shared RetentionOverrides port; absent (org, scope) = the platform default applies. Owned by the
-- scheduler module (the retention home). Notification deliveries are NOT here: they are keyed by
-- recipient, not org, so a per-org retention has no meaning. Config data, NOT soft-deletable.
create table org_retention_override
(
    id             uuid        not null primary key,
    org_id         uuid        not null,          -- soft ref (no cross-module FK)
    scope          varchar(40) not null,          -- RetentionScope: WEBHOOK_DELIVERY | EXCHANGE_JOB
    retention_days int         not null,
    version        bigint      not null,
    created_at     timestamptz not null,
    created_by     varchar(100),
    updated_at     timestamptz,
    updated_by     varchar(100),
    constraint uq_org_retention_override unique (org_id, scope)
);
