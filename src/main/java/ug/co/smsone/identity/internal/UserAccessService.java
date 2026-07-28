package ug.co.smsone.identity.internal;

import java.time.Clock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import ug.co.smsone.identity.ProvisioningStatus;

/** Access decision for an authenticated subject, with lazy INVITED → ACTIVE activation. */
@Service
class UserAccessService {

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
            return Decision.NOT_PROVISIONED; // no JIT: a valid JWT is not access
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
            return Decision.NOT_PROVISIONED;
        }
        return user.getStatus() == ProvisioningStatus.DISABLED ? Decision.DISABLED : Decision.ALLOWED;
    }
}
