package ug.co.smsone.profile.internal;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** The caller's own record. Contacts ride as element rows — their lifecycle IS the profile's. */
@Entity
@Table(name = "user_profile")
@SQLDelete(sql = "update user_profile set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class UserProfile extends SoftDeletableEntity {

    @Column(nullable = false, updatable = false, length = 64)
    private String subject;

    @Column(name = "display_name", length = 150)
    private String displayName;

    @Column(length = 30)
    private String phone;

    @Column(length = 50)
    private String timezone;

    @Column(length = 20)
    private String locale;

    @Column(name = "avatar_key", length = 300)
    private String avatarKey;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_contact", joinColumns = @JoinColumn(name = "profile_id"))
    private List<Contact> contacts = new ArrayList<>();

    @Embeddable
    record Contact(
            @Column(nullable = false, length = 10) String kind,
            @Column(name = "contact_value", nullable = false, length = 150) String value,
            @Column(length = 50) String label,
            @Column(name = "is_primary", nullable = false) boolean primary) {
    }

    protected UserProfile() {
        // JPA
    }

    static UserProfile of(String subject) {
        UserProfile profile = new UserProfile();
        profile.subject = subject;
        return profile;
    }

    void update(String displayName, String phone, String timezone, String locale, List<Contact> contacts) {
        this.displayName = displayName;
        this.phone = phone;
        this.timezone = timezone;
        this.locale = locale;
        this.contacts.clear();
        this.contacts.addAll(contacts);
    }

    void changeAvatar(String avatarKey) {
        this.avatarKey = avatarKey;
    }

    String getSubject() {
        return subject;
    }

    String getDisplayName() {
        return displayName;
    }

    String getPhone() {
        return phone;
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

    List<Contact> getContacts() {
        return contacts;
    }
}
