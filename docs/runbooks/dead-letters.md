# Runbook — deliveries dead-lettered

**Alert** `smsone-dead-letters` · severity critical · fires on the **first** dead-lettered delivery
(no `for:` grace) — a DEAD row already survived 5 attempts with exponential backoff (10s × 2^(n−1),
capped at 1h), so by the time this fires the problem is at least hours old.

**What it means.** A webhook or notification (email/SMS) that an organization expected was given up
on. The message is not lost — the row holds the full payload and its `last_error` — but nothing
retries it anymore without an operator.

## First five minutes

1. Which queue and why (the counter is tagged):

       sum by (queue, channel, reason) (increase(smsone_deliveries_dead_lettered_total[1h]))

2. Read the dead rows — the stored `last_error` is usually the whole diagnosis:

       select id, org_id, channel, recipient, attempts, last_error, updated_at
       from notification_delivery where status = 'DEAD' order by updated_at desc limit 20;

       select d.id, s.org_id, s.url, d.attempts, d.last_error, d.updated_at
       from webhook_delivery d join webhook_subscription s on s.id = d.subscription_id
       where d.status = 'DEAD' order by d.updated_at desc limit 20;

3. Classify: **one org** (their receiver is down / their SMS number invalid / their webhook secret
   was rotated receiver-side without telling us) vs **every org on one channel** (our provider:
   Speeda credentials expired, SMTP relay down, egress blocked).

## Diagnosis by reason

| `last_error` shape | Cause | Fix |
|---|---|---|
| Connect timeout / connection refused (webhooks) | The org's receiver endpoint is down | Tell the org (owner contacts are on the org); they redeliver from the portal once it is back |
| 401/403 from receiver | Receiver validates the signature against a rotated/old secret | Org rotates via `POST /webhooks/{id}/rotate-secret` and deploys the new `whsec_` receiver-side FIRST |
| Speeda `F` responses / auth errors | SMS credentials or sender id wrong at the hub/env level | Fix the `SMS_PROVIDER` integration settings (org override or platform default); creds live in `docker/.env`, never in git |
| SMTP errors on every email | Relay/config | `SPRING_MAIL_*` env; Mailpit in dev |
| Serialization/NPE style errors | Our defect | Issue + fix; these are ours, not the org's |

## Remediation

- Fix the cause, then **redeliver**: dead rows are re-armed by flipping them back to PENDING with
  attempts reset — the claim worker (SKIP LOCKED) picks them up on its next tick:

      update webhook_delivery set status = 'PENDING', attempts = 0, next_attempt_at = now()
      where status = 'DEAD' and id in (...);

  (Same shape for `notification_delivery`.) Redeliver selectively — a receiver that is still down
  just burns 5 more attempts.
- Webhook consumers are contractually idempotent (the delivery id is the dedup key), so
  redelivering something that half-arrived is safe by design.

## If it keeps happening

Per-org flapping receivers are the org's operational problem — surface it to them (the developer
portal shows their delivery history and errors; they can self-serve redelivery). Recurring
platform-side dead letters mean our retry ceiling or our provider is wrong for real traffic — bring
data to the change: reasons, counts, orgs affected. Budget: [../SLO.md](../SLO.md) §delivery.
