package ug.co.smsone.shared.error;

import ug.co.smsone.shared.web.ApiSource;

public class ValidationException extends ApiException {

    public ValidationException(String detail) {
        super(ErrorCode.VALIDATION_FAILED, detail);
    }

    public ValidationException(String detail, ApiSource source) {
        super(ErrorCode.VALIDATION_FAILED, detail, source);
    }
}
