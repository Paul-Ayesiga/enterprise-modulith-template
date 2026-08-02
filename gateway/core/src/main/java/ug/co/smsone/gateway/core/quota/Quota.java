package ug.co.smsone.gateway.core.quota;

import java.time.Duration;

/**
 * A consumer's call allowance — {@code limit} requests per {@code window}. Distinct from the token-bucket
 * rate limit (Phase 3): a quota is the plan's contractual ceiling over a longer window (per minute/day/
 * month), resolved from the subscription. {@link #UNLIMITED} means no ceiling (e.g. the top plan).
 */
public record Quota(long limit, Duration window) {

    public static final Quota UNLIMITED = new Quota(-1, Duration.ZERO);

    public boolean isLimited() {
        return limit >= 0 && window != null && !window.isZero();
    }
}
