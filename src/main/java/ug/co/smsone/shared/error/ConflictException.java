package ug.co.smsone.shared.error;

public class ConflictException extends ApiException {

    public ConflictException(String detail) {
        super(ErrorCode.CONFLICT, detail);
    }
}
