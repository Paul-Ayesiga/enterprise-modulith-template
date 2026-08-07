package ug.co.smsone.files;

import java.io.Serial;

/** The requested object key does not exist — a business outcome, not a storage failure. */
public class FileNotFoundException extends FileStorageException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FileNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
