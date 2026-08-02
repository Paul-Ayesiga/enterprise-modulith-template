package ug.co.smsone.gateway.traffic;

import java.util.List;
import org.springframework.cloud.gateway.filter.factory.cache.keygenerator.CacheKeyGenerator;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * A response-cache key generator that folds the caller's tenant into the key, so one tenant can never
 * be served another tenant's cached response. The tenant is read from the {@code X-Tenant-Id} header
 * the edge stamps from the authenticated principal (EdgeAuthorizationFilter runs first, so the header
 * is present by the time the cache filter computes the key); an unauthenticated caller shares a single
 * {@code anon} partition. Everything else (URI, cookies, Vary headers) is SCG's stock key.
 *
 * <p>SCG's {@code ResponseCacheManager} resolves the entry key via {@code generateKey(request, List)}
 * and the metadata key via {@code generateMetadataKey(request, String...)}; both are overridden here.
 */
public class TenantAwareCacheKeyGenerator extends CacheKeyGenerator {

    static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String ANONYMOUS = "anon";

    @Override
    public String generateKey(ServerHttpRequest request, List<String> varyHeaders) {
        return tenant(request) + "|" + super.generateKey(request, varyHeaders);
    }

    @Override
    public String generateMetadataKey(ServerHttpRequest request, String... headers) {
        return tenant(request) + "|" + super.generateMetadataKey(request, headers);
    }

    private static String tenant(ServerHttpRequest request) {
        String tenant = request.getHeaders().getFirst(TENANT_HEADER);
        return tenant == null || tenant.isBlank() ? ANONYMOUS : tenant;
    }
}
