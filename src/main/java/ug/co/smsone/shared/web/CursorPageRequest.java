package ug.co.smsone.shared.web;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

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
}
