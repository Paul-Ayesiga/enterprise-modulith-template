package ug.co.smsone.shared.web;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import ug.co.smsone.shared.error.ValidationException;

/**
 * Resolved from {@code page[size]} and {@code page[after]} query parameters
 * (see {@link CursorPageRequestArgumentResolver}).
 */
public record CursorPageRequest(int size, String after) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public KeysetScrollPosition scrollPosition() {
        return after == null ? ScrollPosition.keyset() : Cursors.decode(after);
    }

    /**
     * Like {@link #scrollPosition()}, but also rejects a cursor whose keys don't match the
     * collection's sort — a syntactically valid cursor minted for a DIFFERENT collection would
     * otherwise blow up inside Spring Data's keyset query as a 500 instead of the client's 4xx.
     */
    public KeysetScrollPosition scrollPosition(Sort expectedSort) {
        KeysetScrollPosition position = scrollPosition();
        if (!position.getKeys().isEmpty()) {
            Set<String> expected = expectedSort.stream()
                    .map(Sort.Order::getProperty)
                    .collect(Collectors.toUnmodifiableSet());
            if (!expected.equals(position.getKeys().keySet())) {
                throw new ValidationException("page[after] is not a valid cursor for this collection.",
                        ApiSource.parameter("page[after]"));
            }
        }
        return position;
    }
}
