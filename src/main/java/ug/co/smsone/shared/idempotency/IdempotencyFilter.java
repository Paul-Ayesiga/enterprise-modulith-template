package ug.co.smsone.shared.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import ug.co.smsone.shared.security.CurrentUserProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;
import ug.co.smsone.shared.web.RequestPaths;

/**
 * HTTP idempotency for unsafe methods: send an {@code Idempotency-Key} header and retries of the
 * same request replay the stored response instead of re-executing. Claim-first design with a
 * takeover lease (a crashed instance never wedges a key); keys are scoped per authenticated
 * principal; only successful outcomes (status &lt; 400) are stored — errors stay retryable.
 * Ordered after the security chain — unauthenticated requests never claim keys.
 */
@Component
@Order(0)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH");

    private final IdempotencyStore store;
    private final EnvelopeErrorWriter errorWriter;
    private final CurrentUserProvider currentUserProvider;
    private final Duration inProgressLease;
    private final int maxBodyBytes;

    public IdempotencyFilter(IdempotencyStore store, EnvelopeErrorWriter errorWriter,
            CurrentUserProvider currentUserProvider, IdempotencyProperties properties) {
        this.store = store;
        this.errorWriter = errorWriter;
        this.currentUserProvider = currentUserProvider;
        this.inProgressLease = properties.inProgressLease();
        this.maxBodyBytes = properties.maxBodyBytes();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdempotencyProperties.class)
    static class PropertiesRegistrar {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(UNSAFE_METHODS.contains(request.getMethod())
                && RequestPaths.of(request).startsWith("/api/")
                && request.getHeader(KEY_HEADER) != null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(KEY_HEADER);
        if (!VALID_KEY.matcher(key).matches()) {
            errorWriter.write(request, response, ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 1-128 characters of [A-Za-z0-9_-].",
                    ApiSource.header(KEY_HEADER));
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            errorWriter.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE,
                    "Idempotent requests are limited to " + maxBodyBytes + " body bytes.",
                    ApiSource.header(KEY_HEADER));
            return;
        }
        String principal = currentPrincipal();
        // The query string is part of the request's identity: same key + same body but different
        // parameters must hit the payload-mismatch 409, not replay the stored response.
        String query = request.getQueryString();
        String requestHash = sha256(request.getMethod() + '\n' + request.getRequestURI()
                + (query == null ? "" : "?" + query) + '\n', body);

        Optional<Instant> claimedAt = store.claim(principal, key, requestHash, inProgressLease);
        if (claimedAt.isPresent()) {
            execute(new CachedBodyRequestWrapper(request, body), response, filterChain,
                    principal, key, claimedAt.get());
        } else {
            respondFromStore(request, response, principal, key, requestHash);
        }
    }

    private void execute(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain, String principal, String key, Instant claimedAt)
            throws ServletException, IOException {
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, responseWrapper);
        } catch (ServletException | IOException | RuntimeException e) {
            safeRelease(principal, key, claimedAt); // failed execution must stay retryable
            throw e;
        }
        try {
            if (responseWrapper.getStatus() < 400) {
                store.complete(principal, key, claimedAt, responseWrapper.getStatus(),
                        new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8),
                        responseWrapper.getContentType());
            } else {
                // 4xx/5xx are not idempotent outcomes: errors have no side effects worth replaying
                store.release(principal, key, claimedAt);
            }
        } catch (RuntimeException e) {
            log.warn("Idempotency bookkeeping failed for key '{}' — releasing: {}", key, e.getMessage());
            safeRelease(principal, key, claimedAt);
        }
        // the business result is already committed — the client must receive it regardless
        responseWrapper.copyBodyToResponse();
    }

    private void respondFromStore(HttpServletRequest request, HttpServletResponse response,
            String principal, String key, String requestHash) throws IOException {
        IdempotencyStore.StoredResponse stored = store.find(principal, key).orElse(null);
        if (stored == null || stored.inProgress()) {
            errorWriter.write(request, response, ErrorCode.CONFLICT,
                    "A request with this Idempotency-Key is still in progress. Retry shortly.",
                    ApiSource.header(KEY_HEADER));
            return;
        }
        if (!stored.requestHash().equals(requestHash)) {
            errorWriter.write(request, response, ErrorCode.CONFLICT,
                    "This Idempotency-Key was already used with a different request payload.",
                    ApiSource.header(KEY_HEADER));
            return;
        }
        response.setStatus(stored.status());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name()); // stored bodies are UTF-8
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (stored.body() != null) {
            response.getWriter().write(stored.body());
        }
    }

    private void safeRelease(String principal, String key, Instant claimedAt) {
        try {
            store.release(principal, key, claimedAt);
        } catch (RuntimeException e) {
            log.warn("Failed to release idempotency key '{}' (lease will expire): {}", key, e.getMessage());
        }
    }

    /**
     * The key's owner. Must be the immutable SUBJECT, never the display name: usernames are mutable and
     * reassignable in Keycloak, so keying on one would let a recycled username inherit — and replay —
     * the previous holder's stored responses, which is a cross-account disclosure, not just bad
     * bookkeeping. Unauthenticated requests share the {@code anonymous} bucket, but they never get this
     * far: this filter runs after the security chain.
     */
    private String currentPrincipal() {
        return currentUserProvider.currentSubject().orElse("anonymous");
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
