package ug.co.smsone.shared.web;

import java.util.List;

/**
 * Unified JSON:API-inspired lite envelope: {@code data} XOR {@code errors}, {@code meta.requestId}
 * always present. One shape for success and error, served as {@code application/json}.
 *
 * <h2>{@code included}: the compound document, and why adding it changed no existing response</h2>
 *
 * <p>{@code included} carries related resources a caller asked for with {@code ?include=…} — today
 * the people behind a member list's {@code personId}s. It is the last component of the record, so
 * every response that does not sideload renders {@code data}, {@code meta}, {@code links} in the
 * order it always did, and {@code spring.jackson.default-property-inclusion: non_null} drops the
 * field entirely. <b>Byte-compatible for every client that never sends the parameter</b>, which is
 * the whole reason the sideload is opt-in rather than the new default shape.
 *
 * <p>Null and empty are different answers and both are reachable: <b>absent</b> means nobody asked,
 * <b>{@code []}</b> means somebody asked and nothing resolved (a page of members whose people have
 * all been erased). {@code non_null} keeps an empty list, so the distinction survives serialization
 * — do not "tidy" it into a null.
 */
public record ApiResponse<T>(T data, List<ApiError> errors, ApiMeta meta, ApiLinks links,
        List<ResourceObject> included) {

    public static <T> ApiResponse<T> of(T data, ApiMeta meta) {
        return new ApiResponse<>(data, null, meta, null, null);
    }

    public static <T> ApiResponse<T> of(T data, ApiMeta meta, ApiLinks links) {
        return new ApiResponse<>(data, null, meta, links, null);
    }

    /** The compound form. {@code included} may be null (nobody asked) or empty (nothing resolved). */
    public static <T> ApiResponse<T> of(T data, ApiMeta meta, ApiLinks links,
            List<ResourceObject> included) {
        return new ApiResponse<>(data, null, meta, links, included);
    }

    public static ApiResponse<Void> errors(List<ApiError> errors, ApiMeta meta) {
        // Never a sideload on the error arm: JSON:API's `included` is defined as resources related to
        // `data`, and this envelope has no `data` here.
        return new ApiResponse<>(null, List.copyOf(errors), meta, null, null);
    }
}
