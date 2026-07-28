package ug.co.smsone.webhooks.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the canonical JSON body that gets signed and delivered:
 * {@code {"event","orgId","occurredAt","data":{...}}}. Hand-built (not via a mapper) so the exact bytes
 * that are signed are the exact bytes sent, with no dependency on mapper configuration.
 */
final class WebhookPayload {

    private final String event;
    private final UUID orgId;
    private final Instant occurredAt;
    private final Map<String, String> data = new LinkedHashMap<>();

    private WebhookPayload(String event, UUID orgId, Instant occurredAt) {
        this.event = event;
        this.orgId = orgId;
        this.occurredAt = occurredAt;
    }

    static WebhookPayload of(String event, UUID orgId, Instant occurredAt) {
        return new WebhookPayload(event, orgId, occurredAt);
    }

    WebhookPayload with(String key, String value) {
        if (value != null) {
            data.put(key, value);
        }
        return this;
    }

    String toJson() {
        StringBuilder json = new StringBuilder("{")
                .append("\"event\":").append(quote(event))
                .append(",\"orgId\":").append(quote(orgId.toString()))
                .append(",\"occurredAt\":").append(quote(occurredAt.toString()))
                .append(",\"data\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
            first = false;
        }
        return json.append("}}").toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
