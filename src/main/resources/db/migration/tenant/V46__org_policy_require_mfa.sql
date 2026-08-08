-- Org security policy gains require_mfa: human JWT sessions for this org must carry a multi-factor
-- amr claim; invites into the org add CONFIGURE_TOTP so new members enroll at first login.

alter table org_security_policy add column require_mfa boolean not null default false;
