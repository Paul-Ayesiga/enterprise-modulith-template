/**
 * Managed documents: the business record OF a stored file — name, owner, organization, provenance —
 * over storage keys the {@code files} module holds (no S3 type ever appears here). The module owns
 * the catalog and its lifecycle; it deliberately does not own the bytes (that is {@code files}) or
 * moving data between systems (that is {@code exchange}, which registers its artifacts here through
 * the {@code shared.document.Documents} port — a kernel port on the {@code AuditLog} pattern,
 * implemented by this module, so producers need no compile edge into it). Deletion is asymmetric by
 * design: the object goes immediately, the row soft-remains as the metadata trail. Titles are
 * pushed into the search projection on register and removed on delete — the reference producer for
 * the {@code SearchIndex} port.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Document")
package ug.co.smsone.document;
