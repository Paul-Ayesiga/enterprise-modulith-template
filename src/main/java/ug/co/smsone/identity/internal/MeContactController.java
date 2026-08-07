package ug.co.smsone.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The caller's own contact book — the addresses this platform can reach them at, and which of them has
 * been proven.
 *
 * <p>Until this existed the whole surface was read-only: {@code person_contact} modelled labels, a
 * primary flag and a verification date, and the only writer was the provisioning invite. A person whose
 * e-mail changed had no way to say so, and an address nobody had ever proven was being treated as
 * authoritative anyway.
 *
 * <p><b>The list is a plain array, not a cursor page.</b> ADR 0002 bans OFFSET and totals because they
 * scale badly over open collections; this one cannot grow — {@code PersonContacts.MAX_PER_KIND} caps it
 * at ten addresses per kind — so a keyset cursor here would be ceremony no client would ever page
 * through. Same reasoning, and the same shape, as the payment-methods list.
 *
 * <p><b>Verifying does not send anything.</b> There is no e-mailed challenge in this slice; the proof is
 * the identity provider's, and the endpoint spends the claim it already put in the caller's token. See
 * {@link PersonContacts} for what that buys, what it costs, and what an e-mailed challenge would need.
 */
@RestController
@RequestMapping("/api/v1/me/contacts")
class MeContactController {

    private static final String RESOURCE_TYPE = "contact";

    private final PersonContacts contacts;

    MeContactController(PersonContacts contacts) {
        this.contacts = contacts;
    }

    /**
     * {@code kind} is EMAIL, PHONE or OTHER; {@code label} is the person's own name for the address and
     * may be omitted. A record with Strings rather than typed fields on purpose: the kind is a wire
     * vocabulary, and binding it straight to the enum turns a typo into a framework-shaped 400 with no
     * pointer at the offending field. {@code ContactKind.parse} answers a 422 that names it.
     */
    record AddContactRequest(String kind, String value, String label) {
    }

    @GetMapping
    @Operation(summary = "List the addresses this platform can reach you at",
            description = """
                    Best first within each kind: your primary address, then the ones you have proven, \
                    then the rest oldest-first. `verified: false` means exactly that — the platform \
                    will not resolve you by that address and will not fall back to it.""")
    List<ResourceObject> list(CurrentUser user) {
        return contacts.listOwn(requirePerson(user)).stream().map(MeContactController::toResource).toList();
    }

    @PostMapping
    @Operation(summary = "Add a contact address",
            description = """
                    Records an address you claim. It arrives UNVERIFIED, which means inert: nothing \
                    resolves you by it, no invite matches it, and it can never become your primary \
                    until it is proven. Adding an address somebody else has already claimed is \
                    deliberately allowed — an unproven claim costs them nothing, and refusing would \
                    turn this into a way to test which addresses have accounts. The collision is \
                    settled at verification, where only one account can win.""")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject add(@RequestBody AddContactRequest request, CurrentUser user) {
        return toResource(contacts.add(requirePerson(user), ContactKind.parse(request.kind()),
                request.value(), request.label()));
    }

    /**
     * The proof is the token's own {@code email} + {@code email_verified} pair. Read here, at the edge,
     * for the same reason {@link CallerSubject} exists: a provider's claims are translated at the door
     * and never travel further, so the service takes the address as a plain parameter and knows nothing
     * about how it was established.
     */
    @PostMapping("/{id}/verification")
    @Operation(summary = "Prove an address with your sign-in account",
            description = """
                    Marks the address verified if — and only if — the token on this request carries \
                    it as a verified `email` claim, i.e. your identity provider already ran its own \
                    confirmation against that mailbox. This platform sends no message of its own, so \
                    the only address you can prove is the one your sign-in account uses: change it at \
                    the provider, confirm the mail it sends, sign in again, then call this. Answers \
                    409 when the token proves nothing, proves a different address, or when another \
                    account has already proven this one. Verifying your first proven address of a \
                    kind also makes it primary, replacing the unproven placeholder an invite left.""")
    ResourceObject verify(@PathVariable UUID id, CurrentUser user) {
        // Who is calling comes first: a machine key deserves the 403 that says "this is not for you",
        // not a 409 explaining how to confirm a mailbox it will never have.
        UUID personId = requirePerson(user);
        String proven = provenAddress().orElseThrow(() -> new ConflictException(
                "Your sign-in account has not confirmed an e-mail address, so there is nothing to "
                + "prove this with. Confirm it with your identity provider and sign in again."));
        return toResource(contacts.verifyWithProof(personId, id, proven));
    }

    @PutMapping("/{id}/primary")
    @Operation(summary = "Choose your primary address of that kind",
            description = """
                    The primary is the address this platform prefers for you — one per kind, and it \
                    must be verified. Choosing is idempotent, and a refusal never stands your current \
                    primary down.""")
    ResourceObject makePrimary(@PathVariable UUID id, CurrentUser user) {
        return toResource(contacts.makePrimary(requirePerson(user), id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a contact address",
            description = """
                    Refused (409) when it would leave the platform unable to reach or resolve you: \
                    your last e-mail, your last VERIFIED e-mail, or the last one that is primary or \
                    verified — the platform will not silently fall back to an address nobody \
                    established. Removing your primary promotes the best verified address left.""")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id, CurrentUser user) {
        contacts.remove(requirePerson(user), id);
    }

    /**
     * The address the identity provider says this caller controls, or empty.
     *
     * <p>{@code email_verified} is read as an Object and compared loosely on purpose. Keycloak emits a
     * JSON boolean, but the claim reaches us as a string from more than one federation mapper, and
     * {@code Jwt#getClaimAsBoolean} raises a {@link ClassCastException} on the string form — turning a
     * request that should answer a clean 409 into a 500. A claim we cannot read as TRUE is simply not a
     * proof.
     */
    private static Optional<String> provenAddress() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return Optional.empty(); // a machine key proves no mailbox; requirePerson refuses it anyway
        }
        Jwt jwt = token.getToken();
        Object verified = jwt.getClaim("email_verified");
        boolean proven = verified instanceof Boolean flag ? flag
                : verified != null && Boolean.parseBoolean(verified.toString());
        String email = jwt.getClaimAsString("email");
        return proven && email != null && !email.isBlank() ? Optional.of(email.trim()) : Optional.empty();
    }

    /**
     * Contacts belong to a person. A machine key is refused rather than defaulted: an API key has no
     * mailbox, {@code person_contact.person_id} is NOT NULL (V10), and a null would surface as a
     * constraint violation at flush instead of a 403 at the door.
     */
    private static UUID requirePerson(CurrentUser user) {
        if (user.personId() == null) {
            throw new ForbiddenException("Contact addresses belong to a person; this caller is not one.");
        }
        return user.personId();
    }

    /**
     * {@code verified} is published as a boolean beside the instant because that — not the date — is
     * what every client branches on, and a client left to infer it from a null timestamp is one that
     * will eventually infer it wrongly.
     */
    private static ResourceObject toResource(PersonContact contact) {
        return new ResourceObject(contact.getId().toString(), RESOURCE_TYPE, new ContactAttributes(
                contact.getKind().name(), contact.getContactValue(), contact.getLabel(),
                contact.isPrimary(), contact.isVerified(), contact.getVerifiedAt(),
                contact.getCreatedAt()));
    }

    record ContactAttributes(String kind, String value, String label, boolean primary, boolean verified,
            Instant verifiedAt, Instant createdAt) {
    }
}
