package ug.co.smsone.files;

import java.io.Serial;

/** Wraps provider/SDK failures; the message is safe to log but is never sent to clients. */
public class FileStorageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public FileStorageException(String message) {
        super(message);
    }
}
