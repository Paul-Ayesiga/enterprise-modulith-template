package ug.co.smsone.identity.internal;

/**
 * What a session is allowed to do. {@code READ_ONLY} is the default and the overwhelmingly common
 * case: most investigations only need to see what the user sees. {@code WRITE} is a separate, higher
 * tier ({@code platform-admin}) because a write made inside someone's account is indistinguishable
 * from their own to everyone but the audit trail.
 */
enum ImpersonationMode {

    READ_ONLY,
    WRITE;

    boolean writeCapable() {
        return this == WRITE;
    }
}
