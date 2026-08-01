package ug.co.smsone.search;

/**
 * How a module makes its aggregates findable without search learning its domain: push a
 * {@link SearchDoc} on every create/change, remove on delete. Upserts are idempotent per
 * {@code (entityType, entityId)} — safe to call from at-least-once event listeners.
 */
public interface SearchIndex {

    void upsert(SearchDoc doc);

    void remove(String entityType, String entityId);
}
