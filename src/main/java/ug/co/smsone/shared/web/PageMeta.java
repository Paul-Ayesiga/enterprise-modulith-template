package ug.co.smsone.shared.web;

/**
 * Cursor-pagination metadata: no totals by design (keyset pagination needs no COUNT and stays
 * stable under concurrent writes). {@code nextCursor} is null on the last page.
 */
public record PageMeta(int size, int count, boolean hasMore, String nextCursor) {
}
