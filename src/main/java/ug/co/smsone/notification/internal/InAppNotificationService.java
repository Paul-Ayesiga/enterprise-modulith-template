package ug.co.smsone.notification.internal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Read side of in-app notifications: each person sees and marks read only their own. Rows are keyed
 * by {@code person.id} — the identifier this platform mints, stable across every provider the person
 * ever signs in with, and not reassignable the way a {@code preferred_username} is.
 */
@Service
@Transactional
class InAppNotificationService {

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final InAppNotificationRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    InAppNotificationService(InAppNotificationRepository repository,
            CurrentUserProvider currentUserProvider, Clock clock) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Window<InAppNotification> listForCurrentUser(CursorPageRequest page) {
        UUID personId = currentPersonId();
        return repository.findBy(
                (root, query, cb) -> cb.equal(root.get("personId"), personId),
                query -> query.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition(LIST_SORT)));
    }

    InAppNotification markRead(UUID id) {
        UUID personId = currentPersonId();
        if (repository.findByIdAndPersonId(id, personId).isEmpty()) {
            throw new NotFoundException("Notification '" + id + "' does not exist.");
        }
        // Conditional bulk update: idempotent under concurrency (no @Version bump, so a parallel
        // mark-read can never surface as an optimistic-lock 500).
        repository.markReadIfUnread(id, personId, clock.instant());
        return repository.findByIdAndPersonId(id, personId)
                .orElseThrow(() -> new NotFoundException("Notification '" + id + "' does not exist."));
    }

    /**
     * The inbox owner. Two distinct refusals on purpose: no caller at all is a 401, while a caller
     * who is not a person is a 403 — an authenticated API key has no in-app inbox, and saying
     * "authenticate" to something already authenticated sends it round a loop it cannot exit.
     *
     * <p>Neither may degrade to a null key: {@code person_id = null} is a predicate that is never
     * true, so a machine would read "you have no notifications" where the honest answer is that the
     * question does not apply to it.
     */
    private UUID currentPersonId() {
        UUID personId = currentUserProvider.requireCurrentUser().personId();
        if (personId == null) {
            throw new ForbiddenException("In-app notifications belong to a person; this caller is not one.");
        }
        return personId;
    }
}
