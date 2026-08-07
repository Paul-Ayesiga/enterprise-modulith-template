package ug.co.smsone.identity;

import java.util.UUID;

/**
 * Result of provisioning. {@code personId} is {@code person.id} — ours, not a provider's — and is what
 * every other module stores when it needs to name this human.
 *
 * <p>{@code alreadyExisted} is true when the person was already fully provisioned before this call:
 * they had a live Keycloak link. A person row with no link counts as NOT yet provisioned, and the call
 * completes it — including re-sending an invite that was lost the first time. That is the same
 * distinction the old {@code app_user}-row check drew, with the two halves the right way round.
 */
public record ProvisionedPerson(UUID personId, String email, boolean alreadyExisted) {
}
