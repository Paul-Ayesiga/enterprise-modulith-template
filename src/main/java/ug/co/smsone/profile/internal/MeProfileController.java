package ug.co.smsone.profile.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ug.co.smsone.identity.PersonDirectory;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The caller's own display surface — profile, preferences, avatar, linked accounts. No org scope and
 * no platform tier: every row here answers about YOU, whichever axis you arrived from. Avatars follow
 * the files pattern (302 to a presigned URL, bytes never through the API).
 *
 * <p><b>Contacts left this surface with V28.</b> An address that can be verified is identity's row to
 * own ({@code person_contact}, V10), and a whole-document PUT is the one shape that cannot express
 * it: replacing the list would silently discard proof-of-ownership, and a proven address is globally
 * unique, so a blind replace could also collide with a stranger's verified e-mail. Self-service
 * contact editing belongs on identity's own {@code /me} surface — see the gap noted in this slice's
 * report; it is not something profile can keep half of.
 */
@RestController
@RequestMapping("/api/v1/me")
class MeProfileController {

    static final String RESOURCE_TYPE = "profile";

    private final ProfileService profiles;
    private final PersonDirectory persons;

    MeProfileController(ProfileService profiles, PersonDirectory persons) {
        this.profiles = profiles;
        this.persons = persons;
    }

    record ProfilePayload(String displayName, String timezone, String locale) {
    }

    record ProfileAttributes(String displayName, String timezone, String locale, boolean hasAvatar) {
    }

    @GetMapping("/profile")
    @Operation(summary = "Read your profile",
            description = "A person who never saved one still HAS one — empty fields, no 404.")
    ResourceObject profile(CurrentUser user) {
        return toResource(profiles.view(requirePerson(user)));
    }

    @PutMapping("/profile")
    @Operation(summary = "Update your profile",
            description = "Whole-document upsert of the display fields. E-mail and phone are "
                    + "contacts, not profile fields — they live with the person.")
    ResourceObject update(@RequestBody ProfilePayload payload, CurrentUser user) {
        return toResource(profiles.upsert(requirePerson(user), payload.displayName(),
                payload.timezone(), payload.locale()));
    }

    @GetMapping("/preferences")
    @Operation(summary = "Read your preferences (small key/value pairs)")
    Map<String, String> preferences(CurrentUser user) {
        return profiles.preferences(requirePerson(user));
    }

    @PutMapping("/preferences")
    @Operation(summary = "Update preferences additively",
            description = "Only the sent keys change; a null value deletes its key.")
    Map<String, String> putPreferences(@RequestBody Map<String, String> changes, CurrentUser user) {
        return profiles.putPreferences(requirePerson(user), changes);
    }

    @PostMapping("/avatar")
    @Operation(summary = "Upload your avatar",
            description = "Multipart `file`, image/*, 2 MB cap. Replaces any previous avatar.")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject avatar(@RequestParam("file") MultipartFile file, CurrentUser user) {
        return toResource(profiles.changeAvatar(requirePerson(user), file));
    }

    @GetMapping("/avatar")
    @Operation(summary = "Download your avatar", description = "302 to a short-lived presigned URL.")
    ResponseEntity<Void> avatar(CurrentUser user) {
        try {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(profiles.avatarUrl(requirePerson(user)).toURI()).build();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Storage returned a malformed presigned URL", ex);
        }
    }

    @DeleteMapping("/avatar")
    @Operation(summary = "Remove your avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAvatar(CurrentUser user) {
        profiles.deleteAvatar(requirePerson(user));
    }

    @GetMapping("/linked-accounts")
    @Operation(summary = "List your linked identity providers",
            description = "Read-only: linking happens in the provider's own account console, never "
                    + "through this API.")
    List<ResourceObject> linkedAccounts(CurrentUser user) {
        return persons.linkedAccounts(requirePerson(user)).stream()
                .map(account -> new ResourceObject(account.provider(), "linked-account",
                        Map.of("provider", account.provider(), "username", account.username())))
                .toList();
    }

    /**
     * Every row behind {@code /me} keys on a {@code person.id}, so a caller without one has nothing
     * to read or write here. For a machine key that is a permanent answer rather than a provisioning
     * state it could grow out of: an API key is a credential an admin minted, and a credential has no
     * display name, no timezone and no linked identity providers. Humans never reach this — the
     * provisioning gate turns an unprovisioned token away before any controller runs.
     */
    private static UUID requirePerson(CurrentUser user) {
        UUID personId = user.personId();
        if (personId == null) {
            throw new ForbiddenException("These endpoints describe a person; an API key is not one.");
        }
        return personId;
    }

    static ResourceObject toResource(PersonProfile profile) {
        return new ResourceObject(profile.getPersonId().toString(), RESOURCE_TYPE,
                new ProfileAttributes(profile.getDisplayName(), profile.getTimezone(),
                        profile.getLocale(), profile.getAvatarKey() != null));
    }
}
