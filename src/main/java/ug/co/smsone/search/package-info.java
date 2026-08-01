/**
 * Full-text search over a module-local projection ({@code search_document}), queried with Postgres
 * FTS (tsvector + GIN) and a trigram fallback for prefixes and typos — lightning fast without a new
 * engine, and the gate test measures it rather than promising it. The module owns the projection
 * and the query surface; it deliberately does not know any domain: producers feed it through the
 * {@code SearchIndex} port (or the module's own listeners on already-published API events), and a
 * document is whatever its producer said — type, id, title, body, tenant. Rows with a null org are
 * platform-wide and reachable only through the admin search.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Search")
package ug.co.smsone.search;
