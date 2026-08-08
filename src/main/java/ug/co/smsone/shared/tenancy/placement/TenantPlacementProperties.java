package ug.co.smsone.shared.tenancy.placement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.tenancy.placement.*} — the one knob ADR 0010's central decision hangs on.
 *
 * <p>Defaulted here as well as in {@code application.yaml} so a fork that deletes the yaml block, or a
 * test slice that never loads it, still gets the ADR's decision rather than a null.
 *
 * <p><b>The two defaults must agree.</b> They disagreed once — the yaml said one thing and this
 * constructor another — and the symptom is the nastiest shape available: every normal boot reads the
 * yaml and behaves correctly, while a slice that skips it provisions tenants into a different home,
 * so the disagreement only ever shows up in a subset of tests or in one environment.
 * {@code PlacementPolicyDefaultTest} asserts they match.
 */
@ConfigurationProperties(prefix = "app.tenancy.placement")
record TenantPlacementProperties(PlacementPolicy policy) {

    TenantPlacementProperties {
        if (policy == null) {
            policy = PlacementPolicy.SILO_PER_ORG;
        }
    }
}
