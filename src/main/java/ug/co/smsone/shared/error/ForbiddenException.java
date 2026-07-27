package ug.co.smsone.shared.error;

public class ForbiddenException extends ApiException {

    public ForbiddenException(String detail) {
        super(ErrorCode.FORBIDDEN, detail);
    }
}
