package ug.co.smsone.notification.internal;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF guard for caller-supplied outbound HTTP targets (webhook + Slack channels): only http/https,
 * and the host must not resolve to a loopback/private/link-local/special-purpose address — otherwise
 * a crafted recipient could make this server POST to internal endpoints (cloud metadata, admin APIs).
 * Beyond the JDK predicates, this explicitly blocks IPv6 unique-local (fc00::/7 — standard pod
 * addressing in dual-stack clusters, and AWS's IPv6 IMDS fd00:ec2::254), CGNAT 100.64.0.0/10, and
 * the special-purpose IPv4 blocks 0/8, 192.0.0.0/24, 198.18.0.0/15, which
 * {@link InetAddress#isSiteLocalAddress()} does not cover. (Best-effort: resolution happens again at
 * send time; pair with egress network policy for hard guarantees.)
 */
final class SafeHttpTargets {

    private SafeHttpTargets() {
    }

    /** @throws NotificationDeliveryException permanent for contract violations, retryable for DNS blips */
    static void requireSafe(String url, boolean allowPrivateHosts) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new NotificationDeliveryException("Outbound URL is not a valid URI: " + url, true);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new NotificationDeliveryException("Outbound URL must be http(s): " + url, true);
        }
        if (uri.getHost() == null) {
            throw new NotificationDeliveryException("Outbound URL has no host: " + url, true);
        }
        if (allowPrivateHosts) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException ex) {
            // A resolver blip is transient — retry with backoff, never dead-letter on first failure.
            throw new NotificationDeliveryException("Outbound host does not resolve: " + uri.getHost(), false);
        }
        for (InetAddress address : addresses) {
            if (isForbidden(address)) {
                throw new NotificationDeliveryException(
                        "Outbound host resolves to a private/special-purpose address: " + uri.getHost(), true);
            }
        }
    }

    private static boolean isForbidden(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet6Address) {
            return (raw[0] & 0xFE) == 0xFC; // fc00::/7 unique-local (RFC 4193)
        }
        if (address instanceof Inet4Address) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            return first == 0                                             // 0.0.0.0/8 "this network"
                    || (first == 100 && second >= 64 && second <= 127)    // 100.64.0.0/10 CGNAT
                    || (first == 192 && second == 0 && (raw[2] & 0xFF) == 0) // 192.0.0.0/24 special-purpose
                    || (first == 198 && (second == 18 || second == 19));  // 198.18.0.0/15 benchmarking
        }
        return false;
    }
}
