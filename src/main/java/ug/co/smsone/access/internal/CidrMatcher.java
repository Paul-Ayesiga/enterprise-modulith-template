package ug.co.smsone.access.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** IPv4/IPv6 CIDR containment, no dependencies. A malformed rule or address simply does not match. */
final class CidrMatcher {

    private CidrMatcher() {
    }

    static boolean matchesAny(String commaJoinedCidrs, String ip) {
        if (commaJoinedCidrs == null || commaJoinedCidrs.isBlank() || ip == null) {
            return false;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (UnknownHostException ex) {
            return false;
        }
        for (String cidr : commaJoinedCidrs.split(",")) {
            if (matches(cidr.trim(), address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String cidr, InetAddress address) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            return false;
        }
        try {
            InetAddress network = InetAddress.getByName(cidr.substring(0, slash));
            int prefix = Integer.parseInt(cidr.substring(slash + 1));
            byte[] net = network.getAddress();
            byte[] addr = address.getAddress();
            if (net.length != addr.length) {
                return false; // v4 rule vs v6 address (or vice versa)
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (net[i] != addr[i]) {
                    return false;
                }
            }
            int remainingBits = prefix % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (net[fullBytes] & mask) == (addr[fullBytes] & mask);
        } catch (RuntimeException | UnknownHostException ex) {
            return false;
        }
    }
}
