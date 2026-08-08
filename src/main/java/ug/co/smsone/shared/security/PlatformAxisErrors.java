package ug.co.smsone.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;

/**
 * Writes an error envelope on the PLATFORM axis, for the three refusals in this package that render
 * OUTSIDE the window {@link CurrentUserFilter} owns (ADR 0010 §3.4).
 *
 * <p><b>Why rendering an error needs an axis at all — the non-obvious part.</b>
 * {@link EnvelopeErrorWriter} localizes {@code detail} through the {@code translation} catalog, and
 * that catalog is a table ({@code translation}, platform tier, ADR 0010 §2 table 51) behind a
 * {@code @Cacheable} bundle. So on a cold or expired bundle, WRITING A 401 IS A DATABASE READ.
 * {@link ApiAuthenticationEntryPoint} and {@link ApiAccessDeniedHandler} are invoked from inside the
 * security chain ({@code @Order -100}) and {@link ImpersonationFilter} runs at {@code @Order -2} —
 * all three before {@code CurrentUserFilter} ({@code @Order -1}) pins anything. Unpinned, that read
 * resolves {@link Tenant#ABSENT} to the empty {@code no_tenant} schema and the clean 401/403 is
 * served as {@code relation "translation" does not exist} instead. A warm bundle hides it, which is
 * precisely why it is closed here rather than left to surface intermittently once every L1 TTL.
 *
 * <p>The pin is hand-rolled rather than {@link TenantContext#runAsPlatform}: {@code write} throws
 * {@link IOException} and a {@code Runnable} cannot. {@code restore} rather than {@code clear},
 * because a caller that already held a tenant must get it back — an error path may narrow the axis
 * for its own read, never take one away.
 *
 * <p>Deliberately NOT the general answer for every filter that renders an envelope. The six at
 * {@code @Order 0} and later already run inside the pinned window, and routing them through here
 * would state the opposite of what is true about them.
 */
final class PlatformAxisErrors {

    private PlatformAxisErrors() {}

    static void write(EnvelopeErrorWriter writer, HttpServletRequest request, HttpServletResponse response,
            ErrorCode errorCode, String detail, ApiSource source) throws IOException {
        Tenant previous = TenantContext.current();
        TenantContext.setPlatform();
        try {
            writer.write(request, response, errorCode, detail, source);
        } finally {
            TenantContext.restore(previous);
        }
    }
}
