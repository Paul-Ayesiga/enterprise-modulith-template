package ug.co.smsone.gateway.core.web;

import org.springframework.web.server.ServerWebExchange;
import ug.co.smsone.gateway.core.security.EdgePrincipal;

/**
 * The per-request context, reactive-style: typed accessors over {@link ServerWebExchange} attributes
 * (a gateway instance holds no per-request state of its own — statelessness, ADR 0007). Each pipeline
 * stage writes what it resolves — the request id first, then the principal/tenant at authentication —
 * and downstream stages read it. This is the gateway's analogue of the modulith's {@code CurrentUser}+MDC.
 */
public final class GatewayAttributes {

    public static final String REQUEST_ID = "gw.requestId";
    public static final String CORRELATION_ID = "gw.correlationId";
    public static final String TRACE_ID = "gw.traceId";
    public static final String TENANT = "gw.tenant";
    public static final String PRINCIPAL = "gw.principal";

    private GatewayAttributes() {
    }

    public static EdgePrincipal principal(ServerWebExchange exchange) {
        return (EdgePrincipal) exchange.getAttributes().get(PRINCIPAL);
    }

    public static void putPrincipal(ServerWebExchange exchange, EdgePrincipal principal) {
        exchange.getAttributes().put(PRINCIPAL, principal);
        if (principal != null && principal.tenant() != null) {
            exchange.getAttributes().put(TENANT, principal.tenant());
        }
    }

    public static String tenant(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(TENANT);
    }

    public static String requestId(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(REQUEST_ID);
    }

    public static void putRequestId(ServerWebExchange exchange, String requestId) {
        exchange.getAttributes().put(REQUEST_ID, requestId);
    }

    public static String correlationId(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(CORRELATION_ID);
    }

    public static void putCorrelationId(ServerWebExchange exchange, String correlationId) {
        exchange.getAttributes().put(CORRELATION_ID, correlationId);
    }

    public static String traceId(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(TRACE_ID);
    }

    public static void putTraceId(ServerWebExchange exchange, String traceId) {
        exchange.getAttributes().put(TRACE_ID, traceId);
    }
}
