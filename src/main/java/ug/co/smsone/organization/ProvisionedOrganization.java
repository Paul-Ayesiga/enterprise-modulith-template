package ug.co.smsone.organization;

import java.util.UUID;

/**
 * What {@link Organizations#create} produced: the tenant key this platform minted, and the person it
 * provisioned as its owner.
 *
 * <p>Both ids are returned because the one call is the only thing that knows both, and its caller
 * (self-service signup) records both on the request row. Returning only the organization would leave the
 * signup → person relationship recoverable solely by matching the e-mail string back — the recovery V42
 * added {@code signup_request.owner_person_id} to abolish.
 */
public record ProvisionedOrganization(UUID organizationId, UUID ownerPersonId) {
}
