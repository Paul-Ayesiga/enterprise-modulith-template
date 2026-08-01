package ug.co.smsone.shared.document;

import java.util.UUID;

/**
 * Files an artifact a module produced as a managed document: the bytes are already behind the files
 * port under {@code storageKey}; this records whose document that key now is. Returns the new
 * document's id.
 *
 * <p>A shared port — the {@code AuditLog} pattern — so a producer can register documents without
 * depending on the document module. That direction matters: the exchange platform is the reference
 * caller, and {@code organization} implements its handler SPI, so a compile edge from exchange into
 * {@code document} would close the cycle {@code document → search → organization → exchange}. The
 * document module provides the implementation.
 */
public interface Documents {

    UUID register(NewDocument document);
}
