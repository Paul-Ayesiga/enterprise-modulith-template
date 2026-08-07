package ug.co.smsone.identity.internal;

/**
 * A person's name, modelled on SCIM 2.0's {@code name} complex attribute (RFC 7643 §4.1.1) and the
 * OIDC standard claims rather than on first/last.
 *
 * <p><b>{@code formattedName} is the only display value.</b> Never assemble one from the parts: name
 * ORDER is cultural (family-name-first is normal across much of East Asia and in Hungary), so
 * {@code given + " " + family} prints many people's names backwards. The parts exist for sorting,
 * matching and form pre-fill; nothing else.
 *
 * <p>Every component is optional, {@code familyName} included. Mononyms are ordinary, Icelandic names
 * are patronymic rather than familial, and Spanish and Portuguese names carry two surnames — a value
 * carrying nothing but a {@code formattedName} is valid, and so is one carrying nothing at all.
 */
record PersonName(String formattedName, String givenName, String familyName, String middleName,
        String honorificPrefix, String honorificSuffix, String preferredName) {

    /** Nothing known yet — a provisioning request that supplied no name at all. */
    static final PersonName UNKNOWN = new PersonName(null, null, null, null, null, null, null);

    /**
     * Blank is not a name. Normalising to null here rather than at each call site is what keeps
     * "has this person a family name?" a null check everywhere instead of a null-or-blank check in the
     * places somebody remembered.
     */
    PersonName {
        formattedName = blankToNull(formattedName);
        givenName = blankToNull(givenName);
        familyName = blankToNull(familyName);
        middleName = blankToNull(middleName);
        honorificPrefix = blankToNull(honorificPrefix);
        honorificSuffix = blankToNull(honorificSuffix);
        preferredName = blankToNull(preferredName);
    }

    /** The two parts a provisioning request carries. {@code formattedName} stays null — see above. */
    static PersonName ofParts(String givenName, String familyName) {
        return new PersonName(null, givenName, familyName, null, null, null, null);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
