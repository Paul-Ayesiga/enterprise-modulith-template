package ug.co.smsone.gateway.core.lifecycle;

/**
 * Where a route (an API version) sits in its lifecycle. {@code PUBLISHED} routes normally; {@code
 * DEPRECATED} still routes but warns callers (Deprecation/Sunset headers) to migrate; {@code RETIRED}
 * no longer routes at all (410 Gone). (Draft/testing are pre-publication states the edge simply does
 * not carry — an unpublished route is just absent.)
 */
public enum RouteLifecycle {
    PUBLISHED,
    DEPRECATED,
    RETIRED
}
