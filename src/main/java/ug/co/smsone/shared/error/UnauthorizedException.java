package ug.co.smsone.shared.error;

import java.io.Serial;

public class UnauthorizedException extends ApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnauthorizedException(String detail) {
        super(ErrorCode.UNAUTHORIZED, detail);
    }
}
