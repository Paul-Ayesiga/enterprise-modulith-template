package ug.co.smsone.exchange;

import java.util.UUID;

/**
 * What a handler knows about the job it is serving: the tenant and the human who submitted it.
 * {@code requester} is the token subject captured at submit — per-record authorization (e.g. the
 * members escalation guard) resolves THIS subject's permissions at processing time, so a
 * revocation between submit and processing takes effect.
 */
public record ExchangeContext(UUID orgId, String requester) {
}
