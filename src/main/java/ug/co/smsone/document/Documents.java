package ug.co.smsone.document;

import java.util.UUID;

/**
 * How another module files an artifact it produced (the exchange platform is the reference caller):
 * the bytes are already behind the files port under {@code storageKey}; this records whose document
 * that key now is. Returns the new document's id.
 */
public interface Documents {

    UUID register(NewDocument document);
}
