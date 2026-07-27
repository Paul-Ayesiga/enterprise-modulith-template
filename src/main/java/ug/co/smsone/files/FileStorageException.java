package ug.co.smsone.files;

/** Wraps provider/SDK failures; the message is safe to log but is never sent to clients. */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public FileStorageException(String message) {
        super(message);
    }
}
