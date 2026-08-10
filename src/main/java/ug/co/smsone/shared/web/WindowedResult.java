package ug.co.smsone.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Window;

/**
 * Controller return type for cursor-paginated collections. The envelope auto-wrapper turns it into
 * {@code data: [...]} with {@code meta.page} and {@code links.next}.
 *
 * <p>{@code included} is the compound-document arm ({@link ApiResponse}) — the related resources a
 * caller asked for with {@code ?include=…}. It is null on every collection that does not sideload,
 * which is all of them until a handler calls {@link #withIncluded}, and the two-argument constructor
 * is kept so the handlers that build a page by hand ({@code GeoController},
 * {@code SearchQueryService}, {@code ExchangeJobStore}) need no edit to opt out of a feature they
 * never opted into.
 */
public record WindowedResult<T>(List<T> items, PageMeta page, List<ResourceObject> included) {

    /** A page with no sideload — the shape every collection had before compound documents existed. */
    public WindowedResult(List<T> items, PageMeta page) {
        this(items, page, null);
    }

    public static <E, T> WindowedResult<T> of(Window<E> window, CursorPageRequest request, Function<E, T> mapper) {
        String nextCursor = null;
        if (window.hasNext() && !window.isEmpty()) {
            nextCursor = Cursors.encode((KeysetScrollPosition) window.positionAt(window.size() - 1));
        }
        List<T> items = window.getContent().stream().map(mapper).toList();
        return new WindowedResult<>(items, new PageMeta(request.size(), items.size(), window.hasNext(), nextCursor));
    }

    /**
     * The same page, carrying the resources a sideload resolved.
     *
     * <p>Separate from {@link #of} rather than an extra parameter on it: the ids to resolve come from
     * the window, so the sideload cannot be computed until the page exists, and threading a resolver
     * function into {@code of} would put a directory read inside a mapping helper that every
     * collection in the codebase calls.
     *
     * @param included null to sideload nothing, empty when the caller asked and nothing resolved —
     *     the distinction is on the wire and {@link ApiResponse} explains why it is kept
     */
    public WindowedResult<T> withIncluded(List<ResourceObject> included) {
        return new WindowedResult<>(items, page, included);
    }
}
