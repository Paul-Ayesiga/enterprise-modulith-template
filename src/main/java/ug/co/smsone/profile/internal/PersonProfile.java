package ug.co.smsone.profile.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * A person's display preferences: what to call them here, where they are, and what they look like.
 * Keyed on {@code person.id} as a SOFT REF with no foreign key — profile is a plausible service of
 * its own and a constraint cannot cross a network (V28). The cost is that deleting a person does not
 * cascade this row; erasure deletes it explicitly.
 *
 * <p><b>Contacts and phone are not here, and their absence is the point.</b> V28 moved reachability
 * onto {@code person_contact} in the identity module: an address that can be labelled, marked
 * primary and <em>verified</em> is a row with a lifecycle, which a column on a display preference
 * could never be. A person with no profile still has an e-mail, so contacts could not hang off this
 * table without the deletion of a display preference deleting the address the system mails.
 */
@Entity
@Table(name = "person_profile")
@SQLDelete(sql = "update person_profile set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class PersonProfile extends SoftDeletableEntity {

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "display_name", length = 150)
    private String displayName;

    @Column(length = 50)
    private String timezone;

    @Column(length = 20)
    private String locale;

    @Column(name = "avatar_key", length = 300)
    private String avatarKey;

    protected PersonProfile() {
        // JPA
    }

    static PersonProfile of(UUID personId) {
        PersonProfile profile = new PersonProfile();
        profile.personId = personId;
        return profile;
    }

    void update(String displayName, String timezone, String locale) {
        this.displayName = displayName;
        this.timezone = timezone;
        this.locale = locale;
    }

    void changeAvatar(String avatarKey) {
        this.avatarKey = avatarKey;
    }

    UUID getPersonId() {
        return personId;
    }

    String getDisplayName() {
        return displayName;
    }

    String getTimezone() {
        return timezone;
    }

    String getLocale() {
        return locale;
    }

    String getAvatarKey() {
        return avatarKey;
    }
}
