package ug.co.smsone.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import ug.co.smsone.shared.web.ForwardedClientIp.HttpForwardingProperties;

/**
 * The trust rule in isolation: XFF is client-writable, so with 0 declared hops it is ignored
 * entirely; with N hops only the Nth-from-right entry (the first one OUR proxy vouched for) is
 * believed, and a header shorter than the declared proxy line falls back to the socket peer.
 */
class ForwardedClientIpTest {

    private static ForwardedClientIp hops(Integer n) {
        return new ForwardedClientIp(new HttpForwardingProperties(n));
    }

    private static MockHttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return request;
    }

    @Test
    void zeroHopsIgnoresTheHeaderEntirely() {
        assertThat(hops(0).clientIp(request("10.0.0.5", "203.0.113.9"))).isEqualTo("10.0.0.5");
        assertThat(hops(null).clientIp(request("10.0.0.5", "203.0.113.9"))).isEqualTo("10.0.0.5");
    }

    @Test
    void oneHopTakesTheRightmostEntry() {
        // The caller forged the left entry; our single proxy appended the real peer on the right.
        assertThat(hops(1).clientIp(request("172.16.0.1", "1.2.3.4, 203.0.113.9")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void twoHopsTakeTheSecondFromRight() {
        // ingress appended the client, the gateway appended the ingress — the client is len-2.
        assertThat(hops(2).clientIp(request("172.16.0.1", "forged, 203.0.113.9, 10.42.0.7")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void aHeaderShorterThanTheProxyLineFallsBackToTheSocket() {
        // Two declared hops but one entry: the request did not traverse our proxy line.
        assertThat(hops(2).clientIp(request("172.16.0.1", "203.0.113.9"))).isEqualTo("172.16.0.1");
        assertThat(hops(1).clientIp(request("172.16.0.1", null))).isEqualTo("172.16.0.1");
    }

    @Test
    void junkEntriesFallBackToTheSocket() {
        assertThat(hops(1).clientIp(request("172.16.0.1", "x".repeat(80)))).isEqualTo("172.16.0.1");
        assertThat(hops(1).clientIp(request("172.16.0.1", " ,  "))).isEqualTo("172.16.0.1");
    }
}
