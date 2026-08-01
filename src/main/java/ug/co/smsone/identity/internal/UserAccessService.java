package ug.co.smsone.identity.internal;

import java.time.Clock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Access decision for an authenticated subject, with lazy INVITED → ACTIVE activation — plus the
 * module's read-only {@code app_user} queries, so no controller touches the repository (§3.1).
 */
@Service
class UserAccessService {

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    enum Decision {
        ALLOWED,
        NOT_PROVISIONED,
        DISABLED
    }

    private final UserRepository users;
    private final Clock clock;

    UserAccessService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    // Not @Transactional: save() runs in its own transaction so a concurrent-activation optimistic
    // lock failure surfaces HERE (catchable), not at an outer commit the catch could never reach.
    Decision authorize(String subject) {
        User user = users.findBySubject(subject).orElse(null);
        if (user == null) {
            return absent(subject); // no JIT: a valid JWT is not access
        }
        return switch (user.getStatus()) {
            case DISABLED -> Decision.DISABLED;
            case INVITED -> {
                try {
                    user.activate(clock.instant()); // publishes UserActivated
                    users.save(user);
                } catch (OptimisticLockingFailureException ex) {
                    // A parallel first request (typical SPA page load) won the activation race —
                    // the row is ACTIVE either way, so this request is allowed, not a 500.
                }
                yield Decision.ALLOWED;
            }
            case ACTIVE -> Decision.ALLOWED;
        };
    }

    /** Status peek WITHOUT lazy activation — for endpoints exempt from the gate (onboarding /me). */
    Decision peek(String subject) {
        User user = users.findBySubject(subject).orElse(null);
        if (user == null) {
            return absent(subject);
        }
        return user.getStatus() == ProvisioningStatus.DISABLED ? Decision.DISABLED : Decision.ALLOWED;
    }

    /** Display status for the {@code /me} surface — never activates (that is {@link #authorize}'s job). */
    @Transactional(readOnly = true)
    String provisioningStatusOf(String subject) {
        return users.findBySubject(subject).map(user -> user.getStatus().name()).orElse("UNPROVISIONED");
    }

    @Transactional(readOnly = true)
    Window<User> list(CursorPageRequest page) {
        return users.findBy((root, query, cb) -> cb.conjunction(),
                query -> query.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition(LIST_SORT)));
    }

    /**
     * {@code @SQLRestriction} makes a soft-deleted account indistinguishable from one that was never
     * provisioned, and the two must not decide the same way: NOT_PROVISIONED is the LENIENT branch —
     * {@code GET /api/v1/me} passes it through so an un-onboarded user can render onboarding — while
     * DISABLED is a hard stop everywhere. A deleted account answering "onboard me" would be the one
     * place a deletion reads as an invitation.
     */
    private Decision absent(String subject) {
        return users.existsDeletedBySubject(subject) ? Decision.DISABLED : Decision.NOT_PROVISIONED;
    }
}
