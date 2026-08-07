package ug.co.smsone.profile.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
 * A person's own display record: get-or-default (a person without a saved profile still HAS one —
 * empty), whole-document upsert, additive preference puts, and the avatar's
 * bytes-behind-the-files-port lifecycle — new object first, row second, OLD object deleted last, so
 * a failure at any step leaves a working avatar, never a dangling key.
 *
 * <p>Every entry point takes a {@code person.id}. Nothing here has ever needed to know how that
 * person signs in, and now nothing here can.
 */
@Service
class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private static final Duration AVATAR_PRESIGN_TTL = Duration.ofMinutes(10);
    private static final long AVATAR_MAX_BYTES = 2L * 1024 * 1024;

    private final PersonProfileRepository profiles;
    private final PersonPreferenceRepository preferences;
    private final FileStorageProvider storage;
    private final Clock clock;

    ProfileService(PersonProfileRepository profiles, PersonPreferenceRepository preferences,
            FileStorageProvider storage, Clock clock) {
        this.profiles = profiles;
        this.preferences = preferences;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    PersonProfile view(UUID personId) {
        return profiles.findByPersonId(personId).orElseGet(() -> PersonProfile.of(personId));
    }

    @Transactional
    PersonProfile upsert(UUID personId, String displayName, String timezone, String locale) {
        PersonProfile profile = profiles.findByPersonId(personId)
                .orElseGet(() -> PersonProfile.of(personId));
        profile.update(trim(displayName, 150), trim(timezone, 50), trim(locale, 20));
        return profiles.save(profile);
    }

    @Transactional(readOnly = true)
    Map<String, String> preferences(UUID personId) {
        Map<String, String> map = new LinkedHashMap<>();
        preferences.findByPersonIdOrderByPrefKeyAsc(personId)
                .forEach(preference -> map.put(preference.getPrefKey(), preference.getPrefValue()));
        return map;
    }

    /**
     * Additive upsert: only the sent keys change; a null value deletes its key.
     *
     * <p>Validated in full, then loaded in ONE query — because the query count of this endpoint is the
     * CLIENT's to choose otherwise: a body of fifty preferences was fifty selects before any write.
     *
     * <p>The load deliberately does NOT go through {@code findAllById}, which is what this loop was
     * "fixed" to once and which reads as the batch form everywhere else in this codebase. For a
     * COMPOSITE-ID entity Spring Data implements it as a {@code findById} loop, so it issues exactly
     * the N selects it appears to remove — see
     * {@link PersonPreferenceRepository#findByPersonIdAndPrefKeyIn}, which spells the trap out and is
     * the one-round-trip replacement. Keying the working map by pref key rather than by
     * {@code PersonPreference.Key} is part of that: every row here belongs to one person, so the
     * person is a constant of the request and only the key varies.
     */
    @Transactional
    Map<String, String> putPreferences(UUID personId, Map<String, String> changes) {
        Map<String, String> requested = new LinkedHashMap<>();
        changes.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 100) {
                throw new ValidationException("preference keys must be 1–100 characters.",
                        ApiSource.pointer("/data/attributes/preferences"));
            }
            if (value != null && value.length() > 500) {
                throw new ValidationException("preference values cap at 500 characters.",
                        ApiSource.pointer("/data/attributes/preferences"));
            }
            requested.put(key.trim(), value);
        });
        if (requested.isEmpty()) {
            return preferences(personId); // an empty body changes nothing; don't spend an `in ()` query
        }
        Map<String, PersonPreference> stored = new LinkedHashMap<>();
        preferences.findByPersonIdAndPrefKeyIn(personId, requested.keySet())
                .forEach(preference -> stored.put(preference.getPrefKey(), preference));
        Instant now = clock.instant();
        requested.forEach((key, value) -> {
            PersonPreference existing = stored.get(key);
            if (value == null) {
                if (existing != null) {
                    preferences.delete(existing);
                }
            } else if (existing != null) {
                existing.change(value, now);
            } else {
                preferences.save(PersonPreference.of(personId, key, value, now));
            }
        });
        return preferences(personId);
    }

    PersonProfile changeAvatar(UUID personId, MultipartFile file) {
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
        // avatar/p/<person_id>/… — V28's own spelling. The old key embedded the Keycloak sub, which
        // made the object store a third place holding another system's identifier.
        String key = "avatar/p/" + personId + "/" + UUID.randomUUID();
        try (InputStream in = file.getInputStream()) {
            storage.put(key, in, file.getSize(), contentType);
        } catch (IOException ex) {
            throw new ValidationException("Could not read the uploaded avatar.", ApiSource.parameter("file"));
        }
        PersonProfile profile = profiles.findByPersonId(personId)
                .orElseGet(() -> PersonProfile.of(personId));
        String previous = profile.getAvatarKey();
        profile.changeAvatar(key);
        PersonProfile saved = profiles.save(profile);
        deleteQuietly(previous); // old bytes go LAST — any earlier failure leaves a working avatar
        return saved;
    }

    URL avatarUrl(UUID personId) {
        String key = profiles.findByPersonId(personId).map(PersonProfile::getAvatarKey).orElse(null);
        if (key == null || !storage.exists(key)) {
            throw new NotFoundException("No avatar is set.");
        }
        return storage.presignGet(key, AVATAR_PRESIGN_TTL);
    }

    @Transactional
    void deleteAvatar(UUID personId) {
        PersonProfile profile = profiles.findByPersonId(personId).orElse(null);
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
