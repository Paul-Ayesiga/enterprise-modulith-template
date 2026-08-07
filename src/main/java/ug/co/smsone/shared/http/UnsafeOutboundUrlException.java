package ug.co.smsone.shared.http;

import java.io.Serial;

/**
 * Raised when a caller-supplied outbound URL fails the SSRF guard. {@code retryable} is true only for
 * a transient DNS failure (the host may resolve later); a contract violation (bad scheme/host, or a
 * private/special-purpose address) is permanent.
 */
public class UnsafeOutboundUrlException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public UnsafeOutboundUrlException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
