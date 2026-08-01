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

    MEMBER_ADDED("org.member.added", "A user joined the organization"),
    MEMBER_REMOVED("org.member.removed", "A member was removed from the organization"),
    MEMBER_ROLE_CHANGED("org.member.role_changed", "A member's role was reassigned"),
    ROLE_PERMISSIONS_CHANGED("org.role.permissions_changed",
            "A role's permission bundle changed (or the role was deleted)"),
    ORG_STATUS_CHANGED("org.status_changed", "The organization was suspended or reactivated"),
    ORG_DELETED("org.deleted", "The platform deleted the organization — its last outbound event"),
    ORG_SUBSCRIPTION_CHANGED("org.subscription_changed", "The organization's plan or standing changed"),
    EXCHANGE_JOB_COMPLETED("org.exchange.job_completed",
            "An import/export job reached a terminal state (payload carries outcome and counters)");

    private static final Map<String, WebhookEventType> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(WebhookEventType::code, e -> e));

    private final String code;
    private final String description;

    WebhookEventType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    String code() {
        return code;
    }

    String description() {
        return description;
    }

    static boolean isValid(String code) {
        return BY_CODE.containsKey(code);
    }
}
