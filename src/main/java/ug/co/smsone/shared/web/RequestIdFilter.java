package ug.co.smsone.shared.web;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the public, client-facing request id: accepts a well-formed inbound
 * {@code X-Request-Id} / {@code X-Correlation-Id} (anti log-forging: ≤64 chars, [A-Za-z0-9_-])
 * or mints a ULID. The id goes into the MDC and onto every response as {@code X-Request-Id}.
 * traceId/spanId stay internal — never on the wire.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolve(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolve(HttpServletRequest request) {
        String inbound = request.getHeader(REQUEST_ID_HEADER);
        if (inbound == null) {
            inbound = request.getHeader(CORRELATION_ID_HEADER);
        }
        if (inbound != null && VALID_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UlidCreator.getUlid().toString();
    }
}
