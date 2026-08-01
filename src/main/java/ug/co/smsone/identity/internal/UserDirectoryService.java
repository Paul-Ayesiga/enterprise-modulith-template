package ug.co.smsone.identity.internal;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import ug.co.smsone.identity.UserDirectory;

/** Implements the {@link UserDirectory} public read port over the local {@code app_user} projection. */
@Service
class UserDirectoryService implements UserDirectory {

    private final UserRepository users;

    UserDirectoryService(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<String> findSubjectByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return users.findFirstByEmailIgnoreCaseOrderByProvisionedAtAsc(email.trim())
                .map(User::getSubject);
    }

    @Override
    public Map<String, String> emailsBySubjects(Collection<String> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return Map.of();
        }
        return users.findBySubjectIn(subjects).stream()
                .collect(Collectors.toUnmodifiableMap(User::getSubject, User::getEmail));
    }
}
