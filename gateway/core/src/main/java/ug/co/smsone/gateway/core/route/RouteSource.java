package ug.co.smsone.gateway.core.route;

import java.util.List;

/**
 * Port — where route definitions come from. A static config adapter backs it today; an admin store
 * or a discovery integration can back it later without the core changing.
 */
public interface RouteSource {

    List<RouteDefinition> routes();
}
