package ug.co.smsone.billing.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trial-on-signup. When {@code enabled}, a newly registered org auto-starts a {@code TRIALING} trial
 * of {@code plan} for {@code days} — unless it already has a subscription (a straight-to-paid
 * assignment wins). Off by default; the trial plan must be a PAID plan (FREE cannot be trialed).
 */
@ConfigurationProperties(prefix = "app.billing.trial-on-signup")
record TrialOnSignupProperties(Boolean enabled, String plan, Integer days) {

    TrialOnSignupProperties {
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
        if (plan == null || plan.isBlank()) {
            plan = "PRO";
        }
        if (days == null || days <= 0) {
            days = 30;
        }
    }
}
