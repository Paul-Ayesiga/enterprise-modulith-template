package ug.co.smsone.files;

/** The requested object key does not exist — a business outcome, not a storage failure. */
public class FileNotFoundException extends FileStorageException {

    public FileNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
