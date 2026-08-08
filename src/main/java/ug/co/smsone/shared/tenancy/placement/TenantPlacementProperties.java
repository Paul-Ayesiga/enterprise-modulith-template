package ug.co.smsone.shared.tenancy.placement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.tenancy.placement.*} — the one knob ADR 0010's central decision hangs on.
 *
 * <p>Defaulted here as well as in {@code application.yaml} so a fork that deletes the yaml block, or a
 * test slice that never loads it, still gets the ADR's decision rather than a null.
 */
@ConfigurationProperties(prefix = "app.tenancy.placement")
record TenantPlacementProperties(PlacementPolicy policy) {

    TenantPlacementProperties {
        if (policy == null) {
            policy = PlacementPolicy.POOLED;
        }
    }
}
