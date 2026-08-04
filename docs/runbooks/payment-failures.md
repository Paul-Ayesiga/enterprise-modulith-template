# Runbook — payment failures clustering

**Alert** `smsone-payment-failures` · severity critical · fires when more than 3 payments reach
terminal FAILED at one provider inside 30 minutes.

**What it means.** Individual payment failures are normal (insufficient funds, payer cancels the
handset prompt, wrong PIN). A *cluster at one provider* is not payer behavior — it is expired or
wrong PSP credentials, a sandbox/live mode mix-up, a PSP outage, or our wire contract drifting.
Every failed collection is revenue delayed and, through dunning, an org headed for PAST_DUE/pause —
this alert is the platform losing money quietly.

## First five minutes

1. Which provider, how many:

       sum by (provider) (increase(smsone_payments_outcomes_total{status="FAILED"}[30m]))

2. Read the failed rows — `status_detail` carries the gateway's own words:

       select id, org_id, provider, mode, amount, currency, status_detail, updated_at
       from payment where status = 'FAILED' order by updated_at desc limit 20;

3. Classify by `status_detail`:
   - Same error text on every row → systemic (credentials/config/PSP outage). Continue below.
   - Varied, payer-shaped errors (insufficient funds, timeout on handset) that merely clustered →
     verify with one controlled test payment; if it succeeds, close as coincidence.

## Diagnosis by provider

**Pesapal** — auth errors on `RequestToken` mean consumer key/secret (env `PESAPAL_*`, or the org's
`PAYMENT_GATEWAY` integration override) are wrong for the configured mode; `invalid notification_id`
means the registered IPN went stale (it re-registers lazily — restart clears the cached id);
status_code 2 (FAILED) with a payment-method message is PSP-side. Check mode first: sandbox keys
against the live base URL fail authentication, and `mode` is stamped on every payment row.

**Yo! Uganda** — `Status: ERROR` with an auth StatusMessage means `YO_API_USERNAME/PASSWORD`;
`INDETERMINATE` rows are not failures (keep polling); handset-timeout FAILED rows in bulk usually
mean the MNO (MTN/Airtel mobile money) is degraded, not us — Yo!'s own status page/support confirms.

**Both**: an org can be on its own credentials via the integration hub (org override beats platform
default) — if the cluster is single-org, it is *their* PSP account (suspended, limits, KYC) and the
fix is on their side; owner contacts are on the org.

## Remediation

- **Bad credentials/mode**: fix env (`docker/.env` locally, the secret store in prod) or the
  integration setting; then re-drive one payment end to end in sandbox mode to confirm.
- **PSP outage**: nothing to fix here — note the incident, and rows the PSP later completes are
  picked up by the read-with-refresh path when queried. Terminal FAILED rows never regress; a payer
  simply pays again once the PSP recovers.
- **Stuck PENDING pile-up alongside the failures**: those refresh on read (`GET /payments/{id}`);
  the payments surface stays reachable while a subscription is paused, so orgs can always retry —
  a COMPLETED retry lifts their standing automatically.

## If it keeps happening

Chronic single-provider failure is a commercial signal: switch the platform default provider in the
integration hub (DB config, no deploy) while the PSP recovers. If dunning pauses lapsed orgs during
a long PSP incident, extend `app.billing.dunning.grace-days` for the duration and re-activate the
affected orgs (`markStatus ACTIVE`) once payments clear. Budget: [../SLO.md](../SLO.md) §payments.
