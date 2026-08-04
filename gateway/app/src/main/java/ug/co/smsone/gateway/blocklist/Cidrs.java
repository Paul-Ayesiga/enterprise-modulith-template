package ug.co.smsone.gateway.blocklist;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * CIDR containment for the blocklist, IPv4 and IPv6, no dependencies. A bare address normalizes to
 * its host route ({@code /32} / {@code /128}) so "ban this IP" needs no notation knowledge.
 * Validation is eager and loud — a typo must fail the write (or the boot), never silently match
 * nothing.
 */
final class Cidrs {

    private Cidrs() {
    }

    /** @return the canonical form (always with a prefix), or throws IllegalArgumentException. */
    static String normalize(String cidr) {
        String candidate = cidr == null ? "" : cidr.trim();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("A blocklist entry must be an IP or CIDR.");
        }
        String host = candidate;
        Integer prefix = null;
        int slash = candidate.indexOf('/');
        if (slash >= 0) {
            host = candidate.substring(0, slash);
            try {
                prefix = Integer.parseInt(candidate.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a valid CIDR prefix: " + candidate);
            }
        }
        byte[] address = parse(host, candidate);
        int max = address.length * 8;
        int effective = prefix == null ? max : prefix;
        if (effective < 0 || effective > max) {
            throw new IllegalArgumentException("Prefix out of range for " + candidate + " (0-" + max + ").");
        }
        return host + "/" + effective;
    }

    /** True when {@code ip} (textual) falls inside the already-normalized {@code cidr}. */
    static boolean contains(String cidr, String ip) {
        int slash = cidr.indexOf('/');
        byte[] network;
        byte[] candidate;
        try {
            network = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
            candidate = InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException | RuntimeException e) {
            return false; // an unparseable peer address can never satisfy a block rule
        }
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        if (network.length != candidate.length) {
            return false; // v4 rule vs v6 peer (or vice versa)
        }
        int fullBytes = prefix / 8;
        if (!Arrays.equals(network, 0, fullBytes, candidate, 0, fullBytes)) {
            return false;
        }
        int remainder = prefix % 8;
        if (remainder == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainder);
        return (network[fullBytes] & mask) == (candidate[fullBytes] & mask);
    }

    private static byte[] parse(String host, String original) {
        // InetAddress.getByName would DNS-resolve a hostname — refuse anything that is not a literal.
        if (host.isEmpty() || (!host.contains(":") && !host.matches("[0-9.]+"))) {
            throw new IllegalArgumentException("Not an IP literal: " + original);
        }
        try {
            return InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Not a valid IP address: " + original);
        }
    }
}
