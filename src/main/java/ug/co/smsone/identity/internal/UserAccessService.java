package ug.co.smsone.identity.internal;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    Decision authorize(String subject) {
        User user = users.findBySubject(subject).orElse(null);
        if (user == null) {
            return Decision.NOT_PROVISIONED; // no JIT: a valid JWT is not access
        }
        return switch (user.getStatus()) {
            case DISABLED -> Decision.DISABLED;
            case INVITED -> {
                user.activate(clock.instant()); // publishes UserActivated
                users.save(user);
                yield Decision.ALLOWED;
            }
            case ACTIVE -> Decision.ALLOWED;
        };
    }
}
