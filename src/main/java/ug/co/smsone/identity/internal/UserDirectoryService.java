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
    private final KeycloakUserAdminGateway keycloak;

    UserDirectoryService(UserRepository users, KeycloakUserAdminGateway keycloak) {
        this.users = users;
        this.keycloak = keycloak;
    }

    @Override
    public java.util.List<LinkedAccount> linkedAccounts(String subject) {
        return keycloak.federatedIdentities(subject).stream()
                .map(identity -> new LinkedAccount(
                        String.valueOf(identity.get("identityProvider")),
                        String.valueOf(identity.get("userName"))))
                .toList();
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
