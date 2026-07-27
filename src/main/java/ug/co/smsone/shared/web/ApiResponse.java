package ug.co.smsone.shared.web;

import java.util.List;

/**
 * Unified JSON:API-inspired lite envelope: {@code data} XOR {@code errors}, {@code meta.requestId}
 * always present. One shape for success and error, served as {@code application/json}.
 */
public record ApiResponse<T>(T data, List<ApiError> errors, ApiMeta meta, ApiLinks links) {

    public static <T> ApiResponse<T> of(T data, ApiMeta meta) {
        return new ApiResponse<>(data, null, meta, null);
    }

    public static <T> ApiResponse<T> of(T data, ApiMeta meta, ApiLinks links) {
        return new ApiResponse<>(data, null, meta, links);
    }

    public static ApiResponse<Void> errors(List<ApiError> errors, ApiMeta meta) {
        return new ApiResponse<>(null, List.copyOf(errors), meta, null);
    }
}
