package ug.co.smsone.shared.error;

public class NotFoundException extends ApiException {

    public NotFoundException(String detail) {
        super(ErrorCode.RESOURCE_NOT_FOUND, detail);
    }
}
