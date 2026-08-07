package ug.co.smsone.shared.error;

import java.io.Serial;

public class ConflictException extends ApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictException(String detail) {
        super(ErrorCode.CONFLICT, detail);
    }
}
