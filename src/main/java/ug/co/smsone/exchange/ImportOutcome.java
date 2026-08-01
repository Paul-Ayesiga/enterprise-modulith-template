package ug.co.smsone.exchange;

/** What applying one record did. SKIPPED is a successful no-op (idempotent replay, already-present). */
public enum ImportOutcome {
    APPLIED,
    SKIPPED
}
