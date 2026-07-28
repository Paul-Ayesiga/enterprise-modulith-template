package ug.co.smsone.notification.internal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.UnauthorizedException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Read side of in-app notifications: each user sees and marks read only their own. Rows are keyed
 * by the immutable Keycloak subject — a mutable {@code preferred_username} could be renamed and
 * reassigned, handing the new holder the previous user's notifications.
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
        String recipient = currentSubject();
        return repository.findBy(
                (root, query, cb) -> cb.equal(root.get("recipient"), recipient),
                query -> query.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition(LIST_SORT)));
    }

    InAppNotification markRead(UUID id) {
        String recipient = currentSubject();
        if (repository.findByIdAndRecipient(id, recipient).isEmpty()) {
            throw new NotFoundException("Notification '" + id + "' does not exist.");
        }
        // Conditional bulk update: idempotent under concurrency (no @Version bump, so a parallel
        // mark-read can never surface as an optimistic-lock 500).
        repository.markReadIfUnread(id, recipient, clock.instant());
        return repository.findByIdAndRecipient(id, recipient)
                .orElseThrow(() -> new NotFoundException("Notification '" + id + "' does not exist."));
    }

    private String currentSubject() {
        return currentUserProvider.currentUser()
                .map(CurrentUser::subject)
                .orElseThrow(() -> new UnauthorizedException("Authentication required."));
    }
}
