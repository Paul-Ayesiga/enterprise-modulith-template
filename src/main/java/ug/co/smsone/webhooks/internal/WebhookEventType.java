package ug.co.smsone.webhooks.internal;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The fixed catalog of subscribable event codes — the vocabulary a subscription selects from. Kept
 * decoupled from the internal domain-event class names so the public contract is stable; the listeners
 * map each organization event to one of these codes.
 */
enum WebhookEventType {

    MEMBER_ADDED("org.member.added"),
    MEMBER_REMOVED("org.member.removed"),
    MEMBER_ROLE_CHANGED("org.member.role_changed"),
    ROLE_PERMISSIONS_CHANGED("org.role.permissions_changed"),
    ORG_STATUS_CHANGED("org.status_changed");

    private static final Map<String, WebhookEventType> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(WebhookEventType::code, e -> e));

    private final String code;

    WebhookEventType(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }

    static boolean isValid(String code) {
        return BY_CODE.containsKey(code);
    }
}
