package ug.co.smsone.shared.http;

/**
 * Raised when a caller-supplied outbound URL fails the SSRF guard. {@code retryable} is true only for
 * a transient DNS failure (the host may resolve later); a contract violation (bad scheme/host, or a
 * private/special-purpose address) is permanent.
 */
public class UnsafeOutboundUrlException extends RuntimeException {

    private final boolean retryable;

    public UnsafeOutboundUrlException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
