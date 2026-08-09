package ug.co.smsone.files;

import java.util.List;

/**
 * One page of a prefix listing: the keys, lexicographically ordered, and whether the store holds more
 * after them.
 *
 * <p><b>{@code more} is not "the page came back full", and that distinction is the whole reason this is
 * a record rather than a {@code List<String>}.</b> S3's contract permits a store to return fewer keys
 * than {@code maxKeys} while more still remain — the response says so with {@code isTruncated}, never
 * by the size of the list. A caller that stops on a short page therefore stops early <em>and silently</em>,
 * which for ADR 0010 §6's object extraction means a bundle short by an unknown number of objects, with
 * nothing anywhere saying so. Carrying the flag out of the store is what lets the caller terminate on
 * the store's own answer instead of on an inference about it.
 *
 * <p>Paging is keyset by construction and there is no other kind on offer: the next page is requested
 * with the last key of this one as {@code startAfter}, so there is no offset to drift and no total to
 * compute (ADR 0002, which the object store happens to satisfy natively).
 */
public record ObjectPage(List<String> keys, boolean more) {

    public ObjectPage {
        keys = List.copyOf(keys);
    }

    /** The cursor for the next page: the last key here, or null when this page is empty. */
    public String lastKey() {
        return keys.isEmpty() ? null : keys.get(keys.size() - 1);
    }
}
