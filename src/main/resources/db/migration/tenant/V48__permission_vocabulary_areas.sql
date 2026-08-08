-- Area permission codes for support / exchange / search / subscription / usage.
--
-- These five surfaces used to ride the org:read umbrella — the vocabulary had no codes of their
-- own, so neither a role nor an API key could be scoped per area. The MCP agent surface made that
-- visible: a minted-down key still saw (and could call) all five areas, because org:read was
-- honestly all there was to check. Seven codes now exist (ticket:read, ticket:write,
-- exchange:read, exchange:submit, search:query, subscription:read, usage:read) and the REST
-- controllers + MCP manifests gate on them.
--
-- ROLES are backfilled behavior-preservingly: every role that held ORG_READ gains all seven, so no
-- human loses any access this migration re-gated (owners narrow custom roles afterward if they
-- want the new granularity). role_permission stores enum NAMES. OWNER also self-heals via
-- RoleSeeder's reconciliation, independently of this backfill.
--
-- API KEYS are deliberately NOT backfilled: a key's grant is exactly the set a human minted — the
-- escalation guard's machine held-set rule depends on that — so silently widening keys here would
-- be wrong. Existing keys NARROW on upgrade (the five areas start denying until the key is
-- re-minted with the new codes). Release-note this on any real deployment.

insert into role_permission (role_id, permission)
select r.role_id, v.permission
from (select distinct role_id from role_permission where permission = 'ORG_READ') r
         cross join (values ('TICKET_READ'), ('TICKET_WRITE'),
                            ('EXCHANGE_READ'), ('EXCHANGE_SUBMIT'),
                            ('SEARCH_QUERY'), ('SUBSCRIPTION_READ'), ('USAGE_READ')) as v(permission)
on conflict do nothing;
