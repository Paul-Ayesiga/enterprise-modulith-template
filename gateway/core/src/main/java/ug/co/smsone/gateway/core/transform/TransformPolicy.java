package ug.co.smsone.gateway.core.transform;

import java.util.List;
import java.util.Map;

/**
 * A route's request/response transformation — how the edge rewrites what flows through it, entirely by
 * configuration. All fields are opt-in; {@link #NONE} transforms nothing.
 *
 * <ul>
 *   <li>{@code rewritePathRegex}/{@code rewritePathReplacement} — rewrite the upstream path (regex).</li>
 *   <li>{@code stripPrefix} — drop N leading path segments before proxying.</li>
 *   <li>{@code setRequestHeaders} — set (overwrite) request headers upstream; the safe way to inject
 *       {@code X-Tenant}/{@code X-Consumer} so a client cannot spoof them.</li>
 *   <li>{@code removeRequestHeaders} — strip request headers (e.g. {@code Authorization}) from the
 *       upstream call.</li>
 *   <li>{@code addRequestParams} — add query parameters upstream.</li>
 *   <li>{@code setResponseHeaders} — set response headers (security/cache headers).</li>
 *   <li>{@code removeResponseHeaders} — strip internal response headers before the client sees them.</li>
 * </ul>
 */
public record TransformPolicy(
        String rewritePathRegex,
        String rewritePathReplacement,
        Integer stripPrefix,
        Map<String, String> setRequestHeaders,
        List<String> removeRequestHeaders,
        Map<String, String> addRequestParams,
        Map<String, String> setResponseHeaders,
        List<String> removeResponseHeaders) {

    public static final TransformPolicy NONE =
            new TransformPolicy(null, null, null, Map.of(), List.of(), Map.of(), Map.of(), List.of());

    public TransformPolicy {
        setRequestHeaders = setRequestHeaders == null ? Map.of() : Map.copyOf(setRequestHeaders);
        removeRequestHeaders = removeRequestHeaders == null ? List.of() : List.copyOf(removeRequestHeaders);
        addRequestParams = addRequestParams == null ? Map.of() : Map.copyOf(addRequestParams);
        setResponseHeaders = setResponseHeaders == null ? Map.of() : Map.copyOf(setResponseHeaders);
        removeResponseHeaders = removeResponseHeaders == null ? List.of() : List.copyOf(removeResponseHeaders);
    }

    public boolean rewritesPath() {
        return rewritePathRegex != null && !rewritePathRegex.isBlank() && rewritePathReplacement != null;
    }

    public boolean stripsPrefix() {
        return stripPrefix != null && stripPrefix > 0;
    }
}
