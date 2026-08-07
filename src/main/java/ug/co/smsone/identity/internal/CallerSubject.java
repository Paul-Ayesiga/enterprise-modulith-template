package ug.co.smsone.identity.internal;

import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The raw {@code sub} of the token on this request — the ONLY read of a provider subject anywhere below
 * the edge, and deliberately a named class rather than an inline {@code SecurityContextHolder} poke so
 * that "who still touches a subject?" stays a one-line grep.
 *
 * <p>It exists for exactly one question the edge refuses to answer: a caller with no {@code person.id}
 * is either <em>not provisioned yet</em> or <em>erased</em>, and those decide oppositely (onboarding vs a
 * hard stop). {@code CurrentUser} collapses both to a null person id on purpose — publishing the
 * difference on a shared port would put a provisioning policy at the edge — so the module that owns
 * {@code external_identity} reads the subject itself and answers it here.
 *
 * <p>Empty for anything that is not an OIDC token: a machine key has no subject, and an impersonated
 * request never needs one (its target is a person by construction, so the absence branch is unreachable).
 * Nothing may use this to identify a caller — {@link PersonResolver} is the only thing permitted to turn
 * a subject into a person, and everything else takes the person.
 */
final class CallerSubject {

    private CallerSubject() {
    }

    static Optional<String> of() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                ? Optional.ofNullable(token.getToken().getSubject())
                : Optional.empty();
    }
}
