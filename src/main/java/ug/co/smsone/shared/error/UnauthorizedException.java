package ug.co.smsone.shared.error;

public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String detail) {
        super(ErrorCode.UNAUTHORIZED, detail);
    }
}
