package ug.co.smsone.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for turning a validated token into a {@code person.id}, implemented by the module that owns
 * {@code external_identity}. The edge holds the only {@code iss}/{@code sub} pair in the system and
 * needs a person; the table that maps one to the other lives in {@code identity} — so the interface
 * lives here and the implementation there, the same seam as {@link OrgAuthorization} and
 * {@link ImpersonationLookup}, and the reason {@code shared} never compile-depends on {@code identity}.
 *
 * <p>With no implementation present every token resolves to no person. Nothing default-grants: a caller
 * with no person id holds no org permissions and is refused by the provisioning gate.
 */
public interface PersonLookup {

    /**
     * The person this issuer's subject belongs to, or empty when nothing is linked to it.
     *
     * <p>Empty is a complete answer, not an error. A validated token proves who an issuer thinks you
     * are; it has never been permission to create an account (AGENTS §1 — no JIT provisioning), so an
     * unknown subject resolves to nothing and the provisioning gate decides what the caller is told.
     *
     * <p><b>No provider argument, deliberately.</b> The edge knows the issuer that signed the token and
     * the subject inside it; which vocabulary word — {@code KEYCLOAK}, {@code GOOGLE}, {@code SAML} —
     * names that issuer is the implementation's business, because deciding it here is exactly the code
     * change that adding a second identity provider must not require. The issuer is what makes a subject
     * unique (V10: a staging realm and a prod realm both emit {@code KEYCLOAK} over disjoint subject
     * spaces), so both arguments are always required and neither may be blank.
     */
    Optional<UUID> personId(String issuer, String externalSubject);
}
