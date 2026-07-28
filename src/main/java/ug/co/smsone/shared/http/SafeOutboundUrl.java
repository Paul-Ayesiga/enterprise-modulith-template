package ug.co.smsone.shared.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * SSRF guard for caller-supplied outbound HTTP targets (webhooks, Slack, tenant integrations): only
 * http/https, and the host must not resolve to a loopback/private/link-local/special-purpose address —
 * otherwise a crafted URL could make this server POST to internal endpoints (cloud metadata, admin
 * APIs). Beyond the JDK predicates it blocks IPv6 unique-local (fc00::/7 — standard pod addressing in
 * dual-stack clusters, and AWS's IPv6 IMDS fd00:ec2::254), NAT64-embedded private IPv4 (64:ff9b::/96),
 * CGNAT 100.64.0.0/10, and the special-purpose IPv4 blocks 0/8, 192.0.0.0/24, 198.18.0.0/15, which
 * {@link InetAddress#isSiteLocalAddress()} misses.
 *
 * <p><b>Residual risk — DNS rebinding.</b> This validates the addresses it resolves, but the HTTP
 * client re-resolves independently at connect time, so a low-TTL attacker-controlled host can pass here
 * and then connect to an internal address (the classic rebinding TOCTOU). Fully closing it means pinning
 * the connection to the validated IP, which isn't clean with {@code java.net.http} over TLS. Treat this
 * guard as the first line and REQUIRE an egress network policy (deny the workload's egress to
 * link-local/metadata/RFC-1918) in any real deployment — do not rely on it alone.
 */
public final class SafeOutboundUrl {

    private SafeOutboundUrl() {
    }

    /** @throws UnsafeOutboundUrlException permanent for contract violations, retryable for DNS blips */
    public static void requireSafe(String url, boolean allowPrivateHosts) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new UnsafeOutboundUrlException("Outbound URL is not a valid URI: " + url, false);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new UnsafeOutboundUrlException("Outbound URL must be http(s): " + url, false);
        }
        if (uri.getHost() == null) {
            throw new UnsafeOutboundUrlException("Outbound URL has no host: " + url, false);
        }
        if (allowPrivateHosts) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException ex) {
            throw new UnsafeOutboundUrlException("Outbound host does not resolve: " + uri.getHost(), true);
        }
        for (InetAddress address : addresses) {
            if (isForbidden(address)) {
                throw new UnsafeOutboundUrlException(
                        "Outbound host resolves to a private/special-purpose address: " + uri.getHost(), false);
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
            if ((raw[0] & 0xFE) == 0xFC) {
                return true; // fc00::/7 unique-local (RFC 4193)
            }
            if (isNat64WellKnown(raw)) {
                // 64:ff9b::/96 embeds an IPv4 in the low 32 bits — re-check it as an IPv4 target.
                return isForbiddenIpv4(raw[12] & 0xFF, raw[13] & 0xFF, raw[14] & 0xFF, raw[15] & 0xFF);
            }
            return false;
        }
        if (address instanceof Inet4Address) {
            return isForbiddenIpv4(raw[0] & 0xFF, raw[1] & 0xFF, raw[2] & 0xFF, raw[3] & 0xFF);
        }
        return false;
    }

    private static boolean isForbiddenIpv4(int first, int second, int third, int fourth) {
        return first == 0                                             // 0.0.0.0/8 "this network"
                || first == 10                                        // 10.0.0.0/8 private
                || first == 127                                       // 127.0.0.0/8 loopback
                || (first == 100 && second >= 64 && second <= 127)    // 100.64.0.0/10 CGNAT
                || (first == 169 && second == 254)                    // 169.254.0.0/16 link-local (IMDS)
                || (first == 172 && second >= 16 && second <= 31)     // 172.16.0.0/12 private
                || (first == 192 && second == 168)                    // 192.168.0.0/16 private
                || (first == 192 && second == 0 && third == 0)        // 192.0.0.0/24 special-purpose
                || (first == 198 && (second == 18 || second == 19));  // 198.18.0.0/15 benchmarking
    }

    private static boolean isNat64WellKnown(byte[] raw) {
        byte[] prefix = {0x00, 0x64, (byte) 0xFF, (byte) 0x9B, 0, 0, 0, 0, 0, 0, 0, 0};
        return Arrays.equals(raw, 0, 12, prefix, 0, 12);
    }
}
