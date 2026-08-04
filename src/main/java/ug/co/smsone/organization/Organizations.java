package ug.co.smsone.organization;

import java.util.UUID;

/**
 * The organization-provisioning port: create an organization with an invited OWNER through the same
 * audited path the platform-admin endpoint uses (Keycloak org, owner account + set-password invite,
 * projection, {@code OrganizationRegistered}). Consumed by self-service signup; throws the standard
 * {@code ConflictException} when the alias is taken so the caller can retry with a variant.
 */
public interface Organizations {

    UUID create(String alias, String name, String ownerEmail, String ownerFirstName, String ownerLastName);
}
