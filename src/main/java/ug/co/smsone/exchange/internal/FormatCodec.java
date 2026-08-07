package ug.co.smsone.exchange.internal;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.util.List;
import java.util.Map;

/**
 * One wire format, both directions, STREAMING only — a reader hands out records from an open
 * stream and a sink writes them to one; neither ever holds the file. Adding a format means adding
 * an implementation, never touching business logic (the guidelines' extensibility rule).
 */
interface FormatCodec {

    String id();

    /** MIME type of files in this format — what artifacts are stored and served as. */
    String contentType();

    String fileExtension();

    /**
     * Structural validation happens here: a source whose shape cannot serve the expected header
     * throws {@link StructureViolation} before any record is processed.
     */
    RecordReader reader(InputStream in, List<String> header) throws IOException;

    RecordSink writer(OutputStream out, List<String> header) throws IOException;

    interface RecordReader extends Closeable {
        /** The next record, or null at end of input. */
        Map<String, String> next() throws IOException;
    }

    interface RecordSink extends Closeable {
        void write(Map<String, String> record) throws IOException;
    }

    /** A whole-file shape problem — the job fails with a curated message; nothing is retried. */
    class StructureViolation extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        StructureViolation(String message) {
            super(message);
        }
    }
}
