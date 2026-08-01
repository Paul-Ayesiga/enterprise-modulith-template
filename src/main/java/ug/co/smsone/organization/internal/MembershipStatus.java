package ug.co.smsone.organization.internal;

/**
 * Currently single-valued on purpose: a member-suspension state was anticipated but never wired —
 * no transition wrote it — and a state nothing can enter only misleads readers into defending
 * against it. Re-add a value together with the behaviour that produces it.
 */
enum MembershipStatus {
    ACTIVE
}
