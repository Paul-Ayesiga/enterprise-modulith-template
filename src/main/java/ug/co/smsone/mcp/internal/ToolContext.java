package ug.co.smsone.mcp.internal;

import java.util.UUID;
import org.springframework.security.core.Authentication;

/**
 * Everything a tool call knows about its caller, resolved ONCE on the servlet thread (where the
 * security context and MDC are guaranteed) and carried through the SDK's transport context —
 * handlers may execute on another thread, so nothing here is ever re-read from a thread-local.
 *
 * @param authentication the authenticated principal (API key or JWT) — permission checks take it
 *                       as an argument, never from the holder
 * @param orgId          the caller's org — the ONLY tenant any tool may touch (null for platform
 *                       keys and org-less tokens; every org-scoped tool then denies)
 * @param subject        durable attribution ({@code key:<id>} or the token subject)
 * @param requestId      the request correlation id every result and error carries
 * @param clientIp       proxy-aware client address, judged against the org's IP allowlist
 */
public record ToolContext(Authentication authentication, UUID orgId, String subject, String requestId,
        String clientIp) {

    /** The transport-context key this rides under between servlet thread and handler. */
    public static final String KEY = "ug.co.smsone.mcp.toolContext";
}
