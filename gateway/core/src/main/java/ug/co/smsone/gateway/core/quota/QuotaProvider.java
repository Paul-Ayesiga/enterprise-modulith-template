package ug.co.smsone.gateway.core.quota;

import reactor.core.publisher.Mono;

/**
 * Port — the {@link Quota} a consumer is entitled to, resolved from its subscription plan. The platform
 * supplies the implementation (an adapter over its subscriptions/entitlements); the core knows only the
 * contract. When no adapter is on the context, quotas are simply not enforced. Return
 * {@link Quota#UNLIMITED} for a consumer with no ceiling.
 */
public interface QuotaProvider {

    Mono<Quota> quotaFor(String consumer);
}
