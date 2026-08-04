package ug.co.smsone.payments.internal;

/**
 * The normalized outcome vocabulary across gateways. Pesapal speaks
 * PENDING/COMPLETED/FAILED/REVERSED/INVALID; Yo! speaks SUCCEEDED/FAILED/PENDING/INDETERMINATE —
 * both map here. Terminal states never regress.
 */
enum PaymentStatus {
    PENDING, COMPLETED, FAILED, REVERSED, INVALID, INDETERMINATE;

    boolean terminal() {
        return this == COMPLETED || this == FAILED || this == REVERSED;
    }
}
