package ug.co.smsone.gateway.core.lifecycle;

/**
 * A route's lifecycle: its {@link RouteLifecycle} {@code status} and, for a deprecated route, an
 * optional {@code sunset} instant (an HTTP-date) advertised in the {@code Sunset} header so callers know
 * when it goes away. {@link #PUBLISHED} is the default — a normal, current route.
 */
public record LifecyclePolicy(RouteLifecycle status, String sunset) {

    public static final LifecyclePolicy PUBLISHED = new LifecyclePolicy(RouteLifecycle.PUBLISHED, null);

    public LifecyclePolicy {
        status = status == null ? RouteLifecycle.PUBLISHED : status;
    }

    public boolean isRetired() {
        return status == RouteLifecycle.RETIRED;
    }

    public boolean isDeprecated() {
        return status == RouteLifecycle.DEPRECATED;
    }
}
