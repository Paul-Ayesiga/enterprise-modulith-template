package ug.co.smsone.shared.error;

import java.io.Serial;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Base of the business exception hierarchy. {@code detail} must be a curated, client-safe string
 * (or i18n key) — never an internal exception message.
 */
public abstract class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final String detail;
    private final transient ApiSource source;

    protected ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    protected ApiException(ErrorCode errorCode, String detail, ApiSource source) {
        super(detail);
        this.errorCode = errorCode;
        this.detail = detail;
        this.source = source;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }

    public ApiSource source() {
        return source;
    }
}
