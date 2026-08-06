package ug.co.smsone.exchange.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JSON Lines: one object per line. Unknown keys are ignored; missing keys read as empty. */
@Component
class JsonlCodec implements FormatCodec {

    private final ObjectMapper mapper;

    JsonlCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "JSONL";
    }

    @Override
    public String contentType() {
        return "application/x-ndjson";
    }

    @Override
    public String fileExtension() {
        return "jsonl";
    }

    @Override
    public RecordReader reader(InputStream in, List<String> header) {
        BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        return new RecordReader() {
            @Override
            public Map<String, String> next() throws IOException {
                String line;
                do {
                    line = lines.readLine();
                    if (line == null) {
                        return null;
                    }
                } while (line.isBlank());
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (RuntimeException ex) {
                    throw new StructureViolation("A line is not valid JSON. JSONL requires one JSON "
                            + "object per line.");
                }
                if (!node.isObject()) {
                    throw new StructureViolation("A line is not a JSON object. JSONL requires one "
                            + "JSON object per line.");
                }
                Map<String, String> map = new LinkedHashMap<>();
                for (String column : header) {
                    JsonNode value = node.get(column);
                    map.put(column, value == null || value.isNull() ? "" : value.asString());
                }
                return map;
            }

            @Override
            public void close() throws IOException {
                lines.close();
            }
        };
    }

    @Override
    public RecordSink writer(OutputStream out, List<String> header) {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        return new RecordSink() {
            @Override
            public void write(Map<String, String> record) throws IOException {
                Map<String, String> ordered = new LinkedHashMap<>();
                for (String column : header) {
                    ordered.put(column, record.getOrDefault(column, ""));
                }
                writer.write(mapper.writeValueAsString(ordered));
                writer.write('\n');
            }

            @Override
            public void close() throws IOException {
                writer.close();
            }
        };
    }
}
