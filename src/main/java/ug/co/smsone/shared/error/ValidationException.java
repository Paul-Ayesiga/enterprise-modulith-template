package ug.co.smsone.shared.error;

import java.io.Serial;
import ug.co.smsone.shared.web.ApiSource;

public class ValidationException extends ApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ValidationException(String detail) {
        super(ErrorCode.VALIDATION_FAILED, detail);
    }

    public ValidationException(String detail, ApiSource source) {
        super(ErrorCode.VALIDATION_FAILED, detail, source);
    }
}
