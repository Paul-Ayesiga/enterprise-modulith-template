package ug.co.smsone.shared.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;

/**
 * HTTP idempotency for unsafe methods: send an {@code Idempotency-Key} header and retries of the
 * same request replay the stored response instead of re-executing. Claim-first design: the key row
 * is claimed before the handler runs, so concurrent duplicates get a 409 instead of racing.
 * Ordered after the security chain — unauthenticated requests never claim keys.
 */
@Component
@Order(0)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH");

    private final IdempotencyStore store;
    private final EnvelopeErrorWriter errorWriter;

    public IdempotencyFilter(IdempotencyStore store, EnvelopeErrorWriter errorWriter) {
        this.store = store;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(UNSAFE_METHODS.contains(request.getMethod())
                && request.getRequestURI().startsWith("/api/")
                && request.getHeader(KEY_HEADER) != null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(KEY_HEADER);
        if (!VALID_KEY.matcher(key).matches()) {
            errorWriter.write(response, ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 1-128 characters of [A-Za-z0-9_-].",
                    ApiSource.header(KEY_HEADER));
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        String requestHash = sha256(request.getMethod() + '\n' + request.getRequestURI() + '\n', body);

        if (store.claim(key, requestHash)) {
            execute(new CachedBodyRequestWrapper(request, body), response, filterChain, key);
        } else {
            respondFromStore(response, key, requestHash);
        }
    }

    private void execute(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain, String key) throws ServletException, IOException {
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, responseWrapper);
        } catch (ServletException | IOException | RuntimeException e) {
            store.release(key); // failed execution must stay retryable
            throw e;
        }
        if (responseWrapper.getStatus() >= 500) {
            store.release(key); // server errors are not idempotent outcomes — let the client retry
        } else {
            store.complete(key, responseWrapper.getStatus(),
                    new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8),
                    responseWrapper.getContentType());
        }
        responseWrapper.copyBodyToResponse();
    }

    private void respondFromStore(HttpServletResponse response, String key, String requestHash)
            throws IOException {
        IdempotencyStore.StoredResponse stored = store.find(key).orElse(null);
        if (stored == null || stored.inProgress()) {
            errorWriter.write(response, ErrorCode.CONFLICT,
                    "A request with this Idempotency-Key is still in progress. Retry shortly.",
                    ApiSource.header(KEY_HEADER));
            return;
        }
        if (!stored.requestHash().equals(requestHash)) {
            errorWriter.write(response, ErrorCode.CONFLICT,
                    "This Idempotency-Key was already used with a different request payload.",
                    ApiSource.header(KEY_HEADER));
            return;
        }
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (stored.body() != null) {
            response.getWriter().write(stored.body());
        }
    }

    private static String sha256(String prefix, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
