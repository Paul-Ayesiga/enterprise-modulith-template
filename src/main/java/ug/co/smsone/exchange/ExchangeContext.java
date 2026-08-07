package ug.co.smsone.exchange;

import java.util.UUID;

/**
 * What a handler knows about the job it is serving: the tenant and the human who submitted it.
 * {@code requesterPersonId} is the {@code person.id} captured at submit — per-record authorization
 * (e.g. the members escalation guard) resolves THIS person's permissions at processing time, so a
 * revocation between submit and processing takes effect.
 *
 * <p>A person id rather than a token subject on purpose: the job outlives the request that submitted
 * it, and re-checking authority hours later against an identifier one provider minted would stop
 * answering the moment that person signs in with another.
 */
public record ExchangeContext(UUID orgId, UUID requesterPersonId) {
}
