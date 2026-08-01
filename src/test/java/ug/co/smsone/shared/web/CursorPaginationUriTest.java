package ug.co.smsone.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The cursor parameters are named {@code page[size]} / {@code page[after]}, and RFC 3986 reserves
 * {@code [} and {@code ]} — Tomcat enforces that across the whole URI, so without
 * {@code server.tomcat.relaxed-query-chars} the connector rejects an unencoded bracket in the QUERY
 * with a bare HTML 400. That happens inside the connector, before the first filter: no envelope, no
 * request id, nothing the application can catch or explain.
 *
 * <p><b>Why this test uses a raw socket.</b> Every convenient tool hides the bug.
 * {@code MockMvc.param(…)} sets parameters directly and never parses a URI at all, which is why
 * {@link CursorPaginationContractTest} — the test written specifically to pin these parameter names —
 * passed the whole time the real server was rejecting them. {@code java.net.URI} refuses to construct
 * the URI, and {@code curl} treats brackets as glob syntax. A socket is the only way to put the exact
 * bytes a browser or Postman sends onto the wire and see what the server does with them.
 *
 * <p>A <b>401 is the pass</b>: it means the connector parsed the request line and handed it to the
 * security chain. Asserting a specific non-400 rather than "not 400" keeps the test honest — a
 * connection error or an empty response would otherwise read as success.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CursorPaginationUriTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    /** The exact request line a client sends when it does not percent-encode. */
    private int statusFor(String requestTarget) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(("GET " + requestTarget + " HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            try (BufferedReader reply = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
                String statusLine = reply.readLine();
                assertThat(statusLine).as("the server closed the connection without replying").isNotNull();
                return Integer.parseInt(statusLine.split(" ")[1]);
            }
        }
    }

    @Test
    void theConnectorAcceptsTheBracketedCursorParametersUnencoded() throws IOException {
        assertThat(statusFor("/api/v1/admin/users?page[size]=20&page[after]=abc"))
                .as("unencoded brackets must reach the application, not die in the URI parser")
                .isEqualTo(401);
    }

    @Test
    void thePercentEncodedSpellingKeepsWorkingToo() throws IOException {
        assertThat(statusFor("/api/v1/admin/users?page%5Bsize%5D=20&page%5Bafter%5D=abc"))
                .isEqualTo(401);
    }

    /**
     * The relaxation is scoped to the two characters the contract needs. A control on a character that
     * is still refused proves the setting did not simply switch URI validation off.
     */
    @Test
    void otherReservedCharactersAreStillRejected() throws IOException {
        assertThat(statusFor("/api/v1/admin/users?page{size}=20")).isEqualTo(400);
    }

    @Test
    void aRequestWithNoQueryIsUnaffected() throws IOException {
        assertThat(statusFor("/api/v1/admin/users")).isEqualTo(401);
    }
}
