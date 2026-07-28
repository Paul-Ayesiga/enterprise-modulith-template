package ug.co.smsone.organization.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dev-only seed of a first organization + owner. The {@code enabled} flag gates the runner itself. */
@ConfigurationProperties(prefix = "app.organization.dev-bootstrap")
record OrgDevBootstrapProperties(String alias, String name, String ownerEmail,
        String ownerFirstName, String ownerLastName) {

    OrgDevBootstrapProperties {
        if (alias == null || alias.isBlank()) {
            alias = "acme";
        }
        if (name == null || name.isBlank()) {
            name = "Acme";
        }
        if (ownerEmail == null || ownerEmail.isBlank()) {
            ownerEmail = "david@smsone.co.ug";
        }
    }
}
