package ug.co.smsone.integration.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.integration.Integrations;

/**
 * The resolution port: the org's own enabled integration, else the platform default, else empty.
 * Settings come back DECRYPTED (secret values through the cipher) because the caller is an in-JVM
 * consumer that needs the creds; the REST surface is what masks.
 */
@Service
class IntegrationsImpl implements Integrations {

    private final IntegrationRepository integrations;
    private final IntegrationSecretCipher cipher;

    IntegrationsImpl(IntegrationRepository integrations, IntegrationSecretCipher cipher) {
        this.integrations = integrations;
        this.cipher = cipher;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResolvedIntegration> resolve(UUID organizationId, Kind kind) {
        Optional<Integration> chosen = Optional.<Integration>empty();
        if (organizationId != null) {
            chosen = integrations.findByOrgIdAndKind(organizationId, kind.name())
                    .filter(Integration::isEnabled);
        }
        if (chosen.isEmpty()) {
            chosen = integrations.findByOrgIdIsNullAndKind(kind.name()).filter(Integration::isEnabled);
        }
        return chosen.map(integration -> {
            Map<String, String> settings = new LinkedHashMap<>();
            for (Integration.Setting setting : integration.getSettings()) {
                settings.put(setting.key(), setting.secret()
                        ? cipher.decrypt(setting.value()) : setting.value());
            }
            return new ResolvedIntegration(integration.getProvider(), settings);
        });
    }
}
