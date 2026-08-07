package ug.co.smsone.shared.error;

import java.io.Serial;

public class NotFoundException extends ApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException(String detail) {
        super(ErrorCode.RESOURCE_NOT_FOUND, detail);
    }
}
