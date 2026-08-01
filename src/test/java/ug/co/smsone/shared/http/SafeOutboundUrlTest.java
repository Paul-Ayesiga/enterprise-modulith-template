package ug.co.smsone.shared.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The SSRF guard, using IP literals so no DNS/network is touched. Covers the address families and the
 * NAT64-embedded and special-purpose ranges the JDK predicates miss.
 */
class SafeOutboundUrlTest {

    @Test
    void rejectsNonHttpAndHostless() {
        assertThatThrownBy(() -> SafeOutboundUrl.requireSafe("ftp://example.com/x", false))
                .isInstanceOf(UnsafeOutboundUrlException.class);
        assertThatThrownBy(() -> SafeOutboundUrl.requireSafe("file:///etc/passwd", false))
                .isInstanceOf(UnsafeOutboundUrlException.class);
    }

    @Test
    void rejectsLoopbackPrivateAndLinkLocal() {
        assertBlocked("http://127.0.0.1/x");
        assertBlocked("http://10.1.2.3/x");
        assertBlocked("http://192.168.0.5/x");
        assertBlocked("http://169.254.169.254/latest/meta-data"); // cloud IMDS
        assertBlocked("http://[::1]/x");
    }

    @Test
    void rejectsSpecialPurposeAndCgnatAndUla() {
        assertBlocked("http://100.64.0.1/x");   // CGNAT
        assertBlocked("http://198.18.0.1/x");   // benchmarking
        assertBlocked("http://[fc00::1]/x");    // IPv6 unique-local
        assertBlocked("http://[fd00:ec2::254]/x"); // AWS IPv6 IMDS
    }

    @Test
    void rejectsNat64EmbeddedPrivateIpv4() {
        // 64:ff9b::a9fe:a9fe embeds 169.254.169.254 (the metadata IP) — must be blocked.
        assertBlocked("http://[64:ff9b::a9fe:a9fe]/latest/meta-data");
    }

    @Test
    void rejects6to4AndTeredoEmbeddedPrivateIpv4() {
        assertBlocked("http://[2002:a00:1::]/x");         // 6to4 embedding 10.0.0.1
        assertBlocked("http://[2002:a9fe:a9fe::]/x");     // 6to4 embedding the metadata IP
        // Teredo: public server (93.184.216.34) but the bit-inverted CLIENT is 192.168.1.1 —
        // the client is the reachable endpoint, so the address must be blocked.
        assertBlocked("http://[2001:0:5db8:d822::3f57:fefe]/x");
        // Same server with a public client (93.184.216.34 inverted): allowed.
        assertThatCode(() -> SafeOutboundUrl.requireSafe("http://[2001:0:5db8:d822::a247:27dd]/x", false))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsAPublicAddress() {
        assertThatCode(() -> SafeOutboundUrl.requireSafe("http://93.184.216.34/x", false))
                .doesNotThrowAnyException();
    }

    @Test
    void allowPrivateHostsBypassesTheAddressCheck() {
        assertThatCode(() -> SafeOutboundUrl.requireSafe("http://127.0.0.1:9000/hook", true))
                .doesNotThrowAnyException();
    }

    private static void assertBlocked(String url) {
        assertThatThrownBy(() -> SafeOutboundUrl.requireSafe(url, false))
                .as(url)
                .isInstanceOf(UnsafeOutboundUrlException.class);
    }
}
