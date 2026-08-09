package ug.co.smsone.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ug.co.smsone.testsupport.AbstractObjectStoreIntegrationTest;

/**
 * Phase 2 gate: put/get/delete/presign/multipart against REAL SeaweedFS 4.40 (never trust S3
 * parity). Presigned URLs are exercised with a plain HTTP client — no SDK on the request path.
 *
 * <p>The container and its property source moved to {@link AbstractObjectStoreIntegrationTest} when a
 * second class needed a real store; see that class for why sharing them is not just tidiness.
 * {@code compliance.internal.TenantObjectExtractionTest} is the sibling, and the division is the usual
 * one: this class pins what the PORT promises about a single object, that one pins what an extraction
 * promises about all of one tenant's.
 */
class FileStorageIntegrationTest extends AbstractObjectStoreIntegrationTest {

    @Autowired
    private FileStorageProvider storage;

    @Test
    void putGetDeleteRoundtrip() throws Exception {
        byte[] payload = "hello seaweed".getBytes();
        storage.put("smoke/hello.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");

        assertThat(storage.exists("smoke/hello.txt")).isTrue();
        assertThat(storage.get("smoke/hello.txt").readAllBytes()).isEqualTo(payload);

        storage.delete("smoke/hello.txt");
        assertThat(storage.exists("smoke/hello.txt")).isFalse();
    }

    @Test
    void presignedGetAndPutWorkWithoutSdk() throws Exception {
        byte[] payload = "presigned content".getBytes();
        storage.put("smoke/presign.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");

        try (HttpClient http = HttpClient.newHttpClient()) {
            URL getUrl = storage.presignGet("smoke/presign.txt", Duration.ofMinutes(5));
            HttpResponse<byte[]> got = http.send(
                    HttpRequest.newBuilder().uri(getUrl.toURI()).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(got.statusCode()).isEqualTo(200);
            assertThat(got.body()).isEqualTo(payload);

            URL putUrl = storage.presignPut("smoke/presign-up.bin", "application/octet-stream",
                    Duration.ofMinutes(5));
            HttpResponse<String> uploaded = http.send(
                    HttpRequest.newBuilder().uri(putUrl.toURI())
                            .header("Content-Type", "application/octet-stream")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray("client upload".getBytes()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(uploaded.statusCode()).isEqualTo(200);
            assertThat(storage.get("smoke/presign-up.bin").readAllBytes())
                    .isEqualTo("client upload".getBytes());
        }
    }

    @Test
    void multipartUploadSurvivesRealSeaweedFs() throws Exception {
        byte[] big = new byte[11 * 1024 * 1024]; // 2 full 5MiB parts + remainder
        new Random(42).nextBytes(big);

        storage.putLarge("smoke/big.bin", new ByteArrayInputStream(big), big.length,
                "application/octet-stream");

        byte[] fetched = storage.get("smoke/big.bin").readAllBytes();
        assertThat(fetched).hasSameSizeAs(big);
        assertThat(fetched).isEqualTo(big);
    }

    /**
     * The bulk-copy listing, and the one thing about it that is easy to get wrong: {@code more} is the
     * store's own {@code isTruncated}, never "the page came back full". Asserted at a page size that
     * divides the object count exactly, because that is where the two readings disagree — a full page
     * with nothing after it must say {@code more = false}, and a caller inferring truncation from the
     * page size would ask for one more page it does not need (or, on a store that returns short pages
     * while truncated, stop early and silently).
     */
    @Test
    void listPagesByKeysetAndReportsTruncationFromTheStoreRatherThanFromThePageSize() {
        String prefix = "smoke/list/" + UUID.randomUUID() + "/";
        for (int i = 0; i < 6; i++) {
            byte[] payload = ("object " + i).getBytes();
            storage.put(prefix + "k" + i, new ByteArrayInputStream(payload), payload.length, "text/plain");
        }

        ObjectPage first = storage.list(prefix, null, 4);
        assertThat(first.keys()).containsExactly(prefix + "k0", prefix + "k1", prefix + "k2", prefix + "k3");
        assertThat(first.more()).isTrue();

        ObjectPage second = storage.list(prefix, first.lastKey(), 4);
        assertThat(second.keys()).containsExactly(prefix + "k4", prefix + "k5");
        assertThat(second.more()).isFalse();

        ObjectPage exactlyFull = storage.list(prefix, null, 6);
        assertThat(exactlyFull.keys()).hasSize(6);
        assertThat(exactlyFull.more()).as("a page that exactly exhausts the prefix is not truncated")
                .isFalse();

        assertThat(storage.list(prefix, prefix + "k5", 4).keys()).isEmpty();
        assertThat(storage.list("smoke/list/" + UUID.randomUUID() + "/", null, 4).keys()).isEmpty();
    }

    /**
     * A prefix is matched literally, and the separator at its end is what makes it a namespace rather
     * than a string match — {@code …/<id>} is also a prefix of {@code …/<id>-two}, so a listing without
     * the trailing separator would carry off a neighbour whose id merely starts with this one's.
     */
    @Test
    void aPrefixEndingInTheSeparatorCannotReachANeighbourWhoseIdMerelyStartsWithIt() {
        String base = "smoke/sep/" + UUID.randomUUID();
        byte[] payload = "x".getBytes();
        storage.put(base + "/mine", new ByteArrayInputStream(payload), payload.length, "text/plain");
        storage.put(base + "-two/theirs", new ByteArrayInputStream(payload), payload.length, "text/plain");

        assertThat(storage.list(base + "/", null, 100).keys()).containsExactly(base + "/mine");
        assertThat(storage.list(base, null, 100).keys())
                .as("without the separator the same call reaches both — which is why the prefix helper "
                        + "appends it")
                .hasSize(2);
    }

    /**
     * {@code open} exists so a copy can be faithful: the stored content type is what a browser sees on
     * the presigned download, and nothing on the application side restates it. Asserted together with
     * the length, since both arrive in the same response and a copy needs both.
     */
    @Test
    void openCarriesTheContentTypeAndLengthTheStoreHolds() throws Exception {
        byte[] payload = "id,name\n1,acme\n".getBytes();
        String key = "smoke/open/" + UUID.randomUUID() + "/members.csv";
        storage.put(key, new ByteArrayInputStream(payload), payload.length, "text/csv");

        try (StoredObject object = storage.open(key)) {
            assertThat(object.key()).isEqualTo(key);
            assertThat(object.contentType()).isEqualTo("text/csv");
            assertThat(object.sizeBytes()).isEqualTo(payload.length);
            assertThat(object.content().readAllBytes()).isEqualTo(payload);
        }

        assertThatThrownBy(() -> storage.open("smoke/open/" + UUID.randomUUID() + "/gone.csv"))
                .as("a missing key is a business outcome, not a storage failure — the distinct type is "
                        + "what keeps it off the circuit breaker")
                .isInstanceOf(FileNotFoundException.class);
    }

    /**
     * {@code write} is {@code open}'s inverse and it takes the multipart decision away from the caller —
     * a module outside {@code files} cannot see the threshold, so one that guessed would either
     * multipart every small object or single-shot a multi-gigabyte export. Both sides of the threshold
     * are asserted, because the branch is invisible in the result.
     */
    @Test
    void writeRoundTripsBothSidesOfTheMultipartThreshold() throws Exception {
        String prefix = "smoke/write/" + UUID.randomUUID() + "/";
        byte[] small = "tiny".getBytes();
        byte[] large = new byte[6 * 1024 * 1024]; // over the 5 MiB threshold: the multipart branch
        new Random(11).nextBytes(large);

        storage.write(new StoredObject(prefix + "small.txt", "text/plain", small.length,
                new ByteArrayInputStream(small)));
        storage.write(new StoredObject(prefix + "large.bin", "application/octet-stream", large.length,
                new ByteArrayInputStream(large)));

        assertThat(storage.list(prefix, null, 10).keys())
                .containsExactly(prefix + "large.bin", prefix + "small.txt");
        try (StoredObject back = storage.open(prefix + "large.bin")) {
            assertThat(back.contentType()).isEqualTo("application/octet-stream");
            assertThat(back.content().readAllBytes()).isEqualTo(large);
        }
        try (StoredObject back = storage.open(prefix + "small.txt")) {
            assertThat(back.contentType()).isEqualTo("text/plain");
            assertThat(back.content().readAllBytes()).isEqualTo(small);
        }
    }

    /** The keys the extraction has to survive: the exchange namespace takes the uploader's filename. */
    @Test
    void keysCarryingSpacesAndNonAsciiRoundTripThroughListingAndOpen() throws Exception {
        String prefix = "smoke/odd/" + UUID.randomUUID() + "/";
        List<String> names = List.of("quarterly report.csv", "prix-café+50%.csv", "a&b=c.csv");
        for (String name : names) {
            byte[] payload = name.getBytes();
            storage.put(prefix + name, new ByteArrayInputStream(payload), payload.length, "text/csv");
        }

        List<String> listed = storage.list(prefix, null, 10).keys();
        assertThat(listed).hasSize(names.size());
        for (String key : listed) {
            try (StoredObject object = storage.open(key)) {
                assertThat(new String(object.content().readAllBytes()))
                        .as("a key that lists but cannot be re-opened is an object an extraction would "
                                + "count and never carry")
                        .isEqualTo(key.substring(prefix.length()));
            }
        }
    }
}
