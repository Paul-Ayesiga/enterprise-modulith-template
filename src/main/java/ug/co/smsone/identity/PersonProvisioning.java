package ug.co.smsone.identity;

/**
 * Public port for admin-driven provisioning. Creates (or reuses) the {@code person}, records the
 * e-mail we reach them at, links the Keycloak account that will authenticate them, and invites it.
 * Idempotent: re-provisioning an already-linked person reuses everything. Called by the organization
 * module when adding a member.
 *
 * <p>The order matters and is the fix this rewrite exists for: <b>the person is created here first</b>
 * and Keycloak's subject is recorded against it. Provisioning used to call Keycloak first and adopt
 * whatever id came back as the identity of the human — which made a second identity provider a
 * migration rather than an insert.
 */
public interface PersonProvisioning {

    ProvisionedPerson provision(ProvisionRequest request);
}
