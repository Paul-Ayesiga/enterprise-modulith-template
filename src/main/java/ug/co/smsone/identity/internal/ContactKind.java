package ug.co.smsone.identity.internal;

import java.util.Arrays;
import java.util.stream.Collectors;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * What a {@link PersonContact} row addresses.
 *
 * <p>Only {@link #EMAIL} is a kind this platform can currently PROVE (the proof is the identity
 * provider's own verified-email claim — see {@code PersonContacts.verifyWithProof}) and the only one
 * anything resolves a person by. A phone number is a record a person keeps for support to read; there is
 * no SMS challenge yet, so it can never be verified and therefore never be primary. That is stated here
 * rather than enforced as a per-kind branch: the rules are uniform — proof unlocks primary, no proof
 * means inert — and a kind with no proof channel simply never reaches the door.
 */
enum ContactKind {

    EMAIL,
    PHONE,
    OTHER;

    /**
     * The wire vocabulary, parsed once at the edge.
     *
     * <p>{@code valueOf} is deliberately not used raw: it throws {@link IllegalArgumentException}, which
     * renders as a 500 on a request whose only fault is a typo. The message lists the vocabulary because
     * a client that guessed wrong cannot discover it from a rejection that only says no.
     */
    static ContactKind parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        for (ContactKind kind : values()) {
            if (kind.name().equalsIgnoreCase(value)) {
                return kind;
            }
        }
        throw new ValidationException("kind must be one of " + Arrays.stream(values())
                .map(Enum::name).collect(Collectors.joining(", ")) + ".",
                ApiSource.pointer("/data/attributes/kind"));
    }
}
