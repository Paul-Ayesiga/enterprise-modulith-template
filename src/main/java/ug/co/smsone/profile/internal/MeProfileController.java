package ug.co.smsone.profile.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
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
import ug.co.smsone.identity.UserDirectory;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The caller's own identity surface — profile, contacts, preferences, avatar, linked accounts.
 * No org scope and no platform tier: every row here answers about YOU, whichever axis you arrived
 * from. Avatars follow the files pattern (302 to a presigned URL, bytes never through the API).
 */
@RestController
@RequestMapping("/api/v1/me")
class MeProfileController {

    static final String RESOURCE_TYPE = "profile";

    private final ProfileService profiles;
    private final UserDirectory userDirectory;

    MeProfileController(ProfileService profiles, UserDirectory userDirectory) {
        this.profiles = profiles;
        this.userDirectory = userDirectory;
    }

    record ContactPayload(String kind, String value, String label, Boolean primary) {
        // Boolean, not boolean: Jackson 3 refuses an ABSENT primitive with a 400 before validation
        // ever runs — absent here simply means "not the primary contact".
    }

    record ProfilePayload(String displayName, String phone, String timezone, String locale,
            List<ContactPayload> contacts) {
    }

    record ProfileAttributes(String displayName, String phone, String timezone, String locale,
            boolean hasAvatar, List<ContactPayload> contacts) {
    }

    @GetMapping("/profile")
    @Operation(summary = "Read your profile",
            description = "A user who never saved one still HAS one — empty fields, no 404.")
    ResourceObject profile(CurrentUser user) {
        return toResource(profiles.view(user.subject()));
    }

    @PutMapping("/profile")
    @Operation(summary = "Update your profile",
            description = "Whole-document upsert; `contacts` replaces the contact list "
                    + "(kind EMAIL | PHONE | OTHER).")
    ResourceObject update(@RequestBody ProfilePayload payload, CurrentUser user) {
        List<UserProfile.Contact> contacts = payload.contacts() == null ? List.of()
                : payload.contacts().stream()
                        .map(contact -> new UserProfile.Contact(
                                contact.kind() == null ? "" : contact.kind().trim().toUpperCase(),
                                contact.value(), contact.label(), Boolean.TRUE.equals(contact.primary())))
                        .toList();
        return toResource(profiles.upsert(user.subject(), payload.displayName(), payload.phone(),
                payload.timezone(), payload.locale(), contacts));
    }

    @GetMapping("/preferences")
    @Operation(summary = "Read your preferences (small key/value pairs)")
    Map<String, String> preferences(CurrentUser user) {
        return profiles.preferences(user.subject());
    }

    @PutMapping("/preferences")
    @Operation(summary = "Update preferences additively",
            description = "Only the sent keys change; a null value deletes its key.")
    Map<String, String> putPreferences(@RequestBody Map<String, String> changes, CurrentUser user) {
        return profiles.putPreferences(user.subject(), changes);
    }

    @PostMapping("/avatar")
    @Operation(summary = "Upload your avatar",
            description = "Multipart `file`, image/*, 2 MB cap. Replaces any previous avatar.")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject avatar(@RequestParam("file") MultipartFile file, CurrentUser user) {
        return toResource(profiles.changeAvatar(user.subject(), file));
    }

    @GetMapping("/avatar")
    @Operation(summary = "Download your avatar", description = "302 to a short-lived presigned URL.")
    ResponseEntity<Void> avatar(CurrentUser user) {
        try {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(profiles.avatarUrl(user.subject()).toURI()).build();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Storage returned a malformed presigned URL", ex);
        }
    }

    @DeleteMapping("/avatar")
    @Operation(summary = "Remove your avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAvatar(CurrentUser user) {
        profiles.deleteAvatar(user.subject());
    }

    @GetMapping("/linked-accounts")
    @Operation(summary = "List your linked identity providers",
            description = "Read-only Keycloak federated identities; linking happens in the IdP's "
                    + "own account console, never through this API.")
    List<ResourceObject> linkedAccounts(CurrentUser user) {
        return userDirectory.linkedAccounts(user.subject()).stream()
                .map(account -> new ResourceObject(account.provider(), "linked-account",
                        Map.of("provider", account.provider(), "username", account.username())))
                .toList();
    }

    static ResourceObject toResource(UserProfile profile) {
        return new ResourceObject(profile.getSubject(), RESOURCE_TYPE,
                new ProfileAttributes(profile.getDisplayName(), profile.getPhone(),
                        profile.getTimezone(), profile.getLocale(), profile.getAvatarKey() != null,
                        profile.getContacts().stream()
                                .map(contact -> new ContactPayload(contact.kind(), contact.value(),
                                        contact.label(), contact.primary()))
                                .toList()));
    }
}
