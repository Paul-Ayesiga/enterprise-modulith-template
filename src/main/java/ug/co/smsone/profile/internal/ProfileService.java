package ug.co.smsone.profile.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * The caller's own record: get-or-default (a user without a saved profile still HAS one — empty),
 * whole-document upsert (contacts ride along), additive preference puts, and the avatar's
 * bytes-behind-the-files-port lifecycle — new object first, row second, OLD object deleted last,
 * so a failure at any step leaves a working avatar, never a dangling key.
 */
@Service
class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private static final Duration AVATAR_PRESIGN_TTL = Duration.ofMinutes(10);
    private static final long AVATAR_MAX_BYTES = 2L * 1024 * 1024;

    private final UserProfileRepository profiles;
    private final UserPreferenceRepository preferences;
    private final FileStorageProvider storage;
    private final Clock clock;

    ProfileService(UserProfileRepository profiles, UserPreferenceRepository preferences,
            FileStorageProvider storage, Clock clock) {
        this.profiles = profiles;
        this.preferences = preferences;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    UserProfile view(String subject) {
        return profiles.findBySubject(subject).orElseGet(() -> UserProfile.of(subject));
    }

    @Transactional
    UserProfile upsert(String subject, String displayName, String phone, String timezone,
            String locale, List<UserProfile.Contact> contacts) {
        for (UserProfile.Contact contact : contacts) {
            if (!List.of("EMAIL", "PHONE", "OTHER").contains(contact.kind())) {
                throw new ValidationException("contact kind must be EMAIL, PHONE or OTHER.",
                        ApiSource.pointer("/data/attributes/contacts"));
            }
            if (contact.value() == null || contact.value().isBlank()) {
                throw new ValidationException("contact value must not be blank.",
                        ApiSource.pointer("/data/attributes/contacts"));
            }
        }
        UserProfile profile = profiles.findBySubject(subject).orElseGet(() -> UserProfile.of(subject));
        profile.update(trim(displayName, 150), trim(phone, 30), trim(timezone, 50),
                trim(locale, 20), contacts);
        return profiles.save(profile);
    }

    @Transactional(readOnly = true)
    Map<String, String> preferences(String subject) {
        Map<String, String> map = new LinkedHashMap<>();
        preferences.findBySubjectOrderByPrefKeyAsc(subject)
                .forEach(preference -> map.put(preference.getPrefKey(), preference.getPrefValue()));
        return map;
    }

    /** Additive upsert: only the sent keys change; a null value deletes its key. */
    @Transactional
    Map<String, String> putPreferences(String subject, Map<String, String> changes) {
        changes.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 100) {
                throw new ValidationException("preference keys must be 1–100 characters.",
                        ApiSource.pointer("/data/attributes/preferences"));
            }
            UserPreference.Key id = new UserPreference.Key(subject, key.trim());
            if (value == null) {
                preferences.findById(id).ifPresent(preferences::delete);
            } else {
                if (value.length() > 500) {
                    throw new ValidationException("preference values cap at 500 characters.",
                            ApiSource.pointer("/data/attributes/preferences"));
                }
                preferences.findById(id).ifPresentOrElse(
                        existing -> existing.change(value, clock.instant()),
                        () -> preferences.save(
                                UserPreference.of(subject, key.trim(), value, clock.instant())));
            }
        });
        return preferences(subject);
    }

    UserProfile changeAvatar(String subject, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Uploaded avatar is empty.", ApiSource.parameter("file"));
        }
        if (file.getSize() > AVATAR_MAX_BYTES) {
            throw new ValidationException("Avatars cap at 2 MB.", ApiSource.parameter("file"));
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType();
        if (!contentType.startsWith("image/")) {
            throw new ValidationException("Avatars must be images (image/*).", ApiSource.parameter("file"));
        }
        String key = "avatar/u/" + subject + "/" + UUID.randomUUID();
        try (InputStream in = file.getInputStream()) {
            storage.put(key, in, file.getSize(), contentType);
        } catch (IOException ex) {
            throw new ValidationException("Could not read the uploaded avatar.", ApiSource.parameter("file"));
        }
        UserProfile profile = profiles.findBySubject(subject).orElseGet(() -> UserProfile.of(subject));
        String previous = profile.getAvatarKey();
        profile.changeAvatar(key);
        UserProfile saved = profiles.save(profile);
        deleteQuietly(previous); // old bytes go LAST — any earlier failure leaves a working avatar
        return saved;
    }

    URL avatarUrl(String subject) {
        String key = profiles.findBySubject(subject).map(UserProfile::getAvatarKey).orElse(null);
        if (key == null || !storage.exists(key)) {
            throw new NotFoundException("No avatar is set.");
        }
        return storage.presignGet(key, AVATAR_PRESIGN_TTL);
    }

    @Transactional
    void deleteAvatar(String subject) {
        UserProfile profile = profiles.findBySubject(subject).orElse(null);
        if (profile == null || profile.getAvatarKey() == null) {
            return; // idempotent: nothing set is the desired end state
        }
        String key = profile.getAvatarKey();
        profile.changeAvatar(null);
        profiles.save(profile);
        deleteQuietly(key);
    }

    private void deleteQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (RuntimeException ex) {
            // The row already moved on; an orphaned old object is a cleanup nit, not a failure.
            log.warn("Old avatar object {} not deleted: {}", key, ex.toString());
        }
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new ValidationException("Field exceeds " + max + " characters.",
                    ApiSource.pointer("/data/attributes"));
        }
        return trimmed;
    }
}
