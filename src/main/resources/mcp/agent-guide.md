# SMS One platform — agent guide

You are connected to the SMS One platform's MCP surface. Everything below is the contract; the
`whoami` tool tells you who you are.

## Scoping — the rule everything follows from

Your API key belongs to exactly one organization. **Every tool operates on that organization** —
there is no organization argument anywhere, and you cannot address another tenant. `tools/list`
already shows only what your key's permissions allow; a hidden tool called directly is denied the
same way (the visible catalog is a courtesy, the per-call check is the boundary).

## Conventions

- **Errors** carry `error.code` (a stable name like `FORBIDDEN`, `VALIDATION_FAILED`,
  `SUBSCRIPTION_PAUSED`), a human `detail`, and a `requestId`. Quote the `requestId` when asking a
  human for help — support can join it to server logs.
- **Every result** carries the `requestId` in `_meta["smsone/requestId"]`.
- **Collections paginate by cursor**: pass `page_size` (max 100) and `page_after` (the previous
  page's `page.nextCursor`). There are never totals.
- **Async work** (exchange exports) follows submit → poll → fetch: `exchange_submit` returns a
  PENDING job, `exchange_job_get` polls it, `exchange_result_url` mints a short-lived download URL
  once COMPLETED. Bulk bytes always move by URL, never through tool results.
- **Writes can be refused platform-wide**: during a RESTRICT maintenance window
  (`SERVICE_UNAVAILABLE`, retry after the window) or while the organization's subscription is
  paused (`SUBSCRIPTION_PAUSED`, read-only until payment). Reads keep working through both.
- **Secrets**: `webhook_create` and `webhook_rotate_secret` return a signing secret exactly once.
  Deliver it to the receiver's configuration immediately; do not repeat it in conversation, do not
  store it in notes.

## Where to learn more

- `smsone://reference/permissions` — every permission code tools can be gated on.
- `smsone://reference/webhook-events` — the event codes `webhook_create` accepts.
- The REST API (same capabilities, same permissions) is documented in the developer portal;
  MCP tools and REST endpoints share one authorization model, so what one may do, the other may.
