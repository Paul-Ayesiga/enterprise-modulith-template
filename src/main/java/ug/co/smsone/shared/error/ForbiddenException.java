package ug.co.smsone.shared.error;

import java.io.Serial;

public class ForbiddenException extends ApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ForbiddenException(String detail) {
        super(ErrorCode.FORBIDDEN, detail);
    }
}
