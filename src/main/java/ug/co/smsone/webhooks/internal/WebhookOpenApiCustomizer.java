package ug.co.smsone.webhooks.internal;

import io.swagger.v3.oas.models.media.Schema;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Types the webhook request's {@code events} array as an ENUM in the spec — read from
 * {@link WebhookEventType} itself, so the spec can never drift from what create/update accept
 * (the reason this is a customizer and not a hardcoded {@code allowableValues} annotation). Lives
 * in this module because the vocabulary does; springdoc collects customizer beans from anywhere.
 */
@Configuration(proxyBeanMethods = false)
class WebhookOpenApiCustomizer {

    @Bean
    OpenApiCustomizer webhookEventsEnumCustomizer() {
        List<String> codes = Arrays.stream(WebhookEventType.values())
                .map(WebhookEventType::code)
                .toList();
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().forEach((name, schema) -> {
                if (!(name.contains("WebhookRequest"))) {
                    return;
                }
                Object events = schema.getProperties() == null ? null : schema.getProperties().get("events");
                if (events instanceof Schema<?> eventsSchema && eventsSchema.getItems() != null) {
                    @SuppressWarnings("unchecked")
                    Schema<String> items = (Schema<String>) eventsSchema.getItems();
                    items.setEnum(codes);
                    items.setDescription("A subscribable event code — the vocabulary at GET /api/v1/webhooks/event-types");
                }
            });
        };
    }
}
