package ug.co.smsone.exchange.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/** RFC-4180 via commons-csv. The header row must EQUAL the handler's template, in order. */
@Component
class CsvCodec implements FormatCodec {

    @Override
    public String id() {
        return "CSV";
    }

    @Override
    public String contentType() {
        return "text/csv";
    }

    @Override
    public String fileExtension() {
        return "csv";
    }

    @Override
    public RecordReader reader(InputStream in, List<String> header) throws IOException {
        CSVParser parser = CSVFormat.RFC4180.builder()
                .setHeader()               // read the header row from the file
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get()
                .parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> found = parser.getHeaderNames();
        if (!header.equals(found)) {
            parser.close();
            throw new StructureViolation("Header mismatch: expected " + header + " but the file has "
                    + found + ". Download the handler's template and keep its column order.");
        }
        Iterator<CSVRecord> records = parser.iterator();
        return new RecordReader() {
            @Override
            public Map<String, String> next() {
                CSVRecord record;
                try {
                    if (!records.hasNext()) {
                        return null;
                    }
                    record = records.next();
                } catch (RuntimeException ex) {
                    // commons-csv surfaces an unclosed quote / bad character mid-file as its own
                    // unchecked types — a FILE problem, same species as a bad header, never infra.
                    throw new StructureViolation("The CSV is malformed (an unclosed quote or "
                            + "invalid character) — fix the file and submit it again.");
                }
                Map<String, String> map = new LinkedHashMap<>();
                for (String column : header) {
                    map.put(column, record.isMapped(column) && record.isSet(column)
                            ? record.get(column) : "");
                }
                return map;
            }

            @Override
            public void close() throws IOException {
                parser.close();
            }
        };
    }

    @Override
    public RecordSink writer(OutputStream out, List<String> header) throws IOException {
        CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                CSVFormat.RFC4180.builder().setHeader(header.toArray(String[]::new)).get());
        return new RecordSink() {
            @Override
            public void write(Map<String, String> record) throws IOException {
                printer.printRecord(header.stream().map(column -> record.getOrDefault(column, "")).toList());
            }

            @Override
            public void close() throws IOException {
                printer.close(true);
            }
        };
    }
}
