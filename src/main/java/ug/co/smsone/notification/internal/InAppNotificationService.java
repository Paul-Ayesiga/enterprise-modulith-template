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

/** Read side of in-app notifications: each user sees and marks read only their own. */
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
        String recipient = currentUsername();
        return repository.findBy(
                (root, query, cb) -> cb.equal(root.get("recipient"), recipient),
                query -> query.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition()));
    }

    InAppNotification markRead(UUID id) {
        String recipient = currentUsername();
        InAppNotification notification = repository.findByIdAndRecipient(id, recipient)
                .orElseThrow(() -> new NotFoundException("Notification '" + id + "' does not exist."));
        notification.markRead(clock.instant());
        return repository.save(notification);
    }

    private String currentUsername() {
        return currentUserProvider.currentUser()
                .map(CurrentUser::username)
                .orElseThrow(() -> new UnauthorizedException("Authentication required."));
    }
}
