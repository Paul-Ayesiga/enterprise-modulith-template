package ug.co.smsone.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Window;

/**
 * Controller return type for cursor-paginated collections. The envelope auto-wrapper turns it into
 * {@code data: [...]} with {@code meta.page} and {@code links.next}.
 */
public record WindowedResult<T>(List<T> items, PageMeta page) {

    public static <E, T> WindowedResult<T> of(Window<E> window, CursorPageRequest request, Function<E, T> mapper) {
        String nextCursor = null;
        if (window.hasNext() && !window.isEmpty()) {
            nextCursor = Cursors.encode((KeysetScrollPosition) window.positionAt(window.size() - 1));
        }
        List<T> items = window.getContent().stream().map(mapper).toList();
        return new WindowedResult<>(items, new PageMeta(request.size(), items.size(), window.hasNext(), nextCursor));
    }
}
