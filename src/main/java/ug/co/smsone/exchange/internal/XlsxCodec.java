package ug.co.smsone.exchange.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * XLSX both ways WITHOUT ever materializing the workbook: reads through POI's SAX event API
 * (constant memory however many rows), writes through {@code SXSSFWorkbook} (a sliding window of
 * rows, the rest already flushed to disk). The SAX API pushes rows at us while {@link RecordReader}
 * is pull-based — a bounded queue and a virtual-thread producer invert that, which costs one thread
 * per open reader and buys true streaming over the format everyone actually emails around.
 * The FIRST sheet is the data; other sheets are ignored, stated in the template's docs.
 */
@Component
class XlsxCodec implements FormatCodec {

    private static final int QUEUE_CAPACITY = 256;
    private static final Object END = new Object();

    @Override
    public String id() {
        return "XLSX";
    }

    @Override
    public String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public String fileExtension() {
        return "xlsx";
    }

    @Override
    public RecordReader reader(InputStream in, List<String> header) throws IOException {
        BlockingQueue<Object> rows = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        Thread producer = Thread.ofVirtual().name("xlsx-reader").start(() -> {
            try (OPCPackage pkg = OPCPackage.open(in)) {
                XSSFReader reader = new XSSFReader(pkg);
                ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
                XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
                if (!sheets.hasNext()) {
                    throw new StructureViolation("The workbook has no sheet.");
                }
                try (InputStream sheet = sheets.next()) {
                    XMLReader parser = org.apache.poi.util.XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(), strings,
                            new RowCollector(rows), false));
                    parser.parse(new InputSource(sheet));
                }
                rows.put(END);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); // consumer closed early; nothing to hand over
            } catch (Exception ex) {
                try {
                    rows.put(ex instanceof StructureViolation sv ? sv
                            : new StructureViolation("The file is not a readable XLSX workbook."));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        return new QueueBackedReader(rows, producer, header);
    }

    @Override
    public RecordSink writer(OutputStream out, List<String> header) {
        // Window of 100 in-memory rows; everything older is already flushed to a temp file.
        Workbook workbook = new SXSSFWorkbook(100);
        Sheet sheet = workbook.createSheet("data");
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < header.size(); i++) {
            headerRow.createCell(i).setCellValue(header.get(i));
        }
        return new RecordSink() {
            private int rowIndex = 1;

            @Override
            public void write(Map<String, String> record) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < header.size(); i++) {
                    row.createCell(i).setCellValue(record.getOrDefault(header.get(i), ""));
                }
            }

            @Override
            public void close() throws IOException {
                // SXSSFWorkbook.close() deletes the flush temp files itself (it calls the now-deprecated
                // dispose() internally, before delegating to the backing workbook), so try-with-resources
                // is the whole cleanup — an explicit dispose() here would only repeat it.
                try (workbook) {
                    workbook.write(out);
                }
            }
        };
    }

    /** SAX push side: one List&lt;String&gt; of cell values per row, in column order. */
    private static final class RowCollector implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final BlockingQueue<Object> rows;
        private List<String> current;
        private int lastColumn;

        private RowCollector(BlockingQueue<Object> rows) {
            this.rows = rows;
        }

        @Override
        public void startRow(int rowNum) {
            current = new ArrayList<>();
            lastColumn = -1;
        }

        @Override
        public void endRow(int rowNum) {
            try {
                rows.put(current);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("XLSX consumer closed", ex);
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = new org.apache.poi.ss.util.CellReference(cellReference).getCol();
            for (int i = lastColumn + 1; i < column; i++) {
                current.add(""); // skipped cells are EMPTY cells, not shifted columns
            }
            current.add(formattedValue == null ? "" : formattedValue);
            lastColumn = column;
        }
    }

    /** Pull side: validates the header row, then maps each value row onto the header keys. */
    private static final class QueueBackedReader implements RecordReader {

        private final BlockingQueue<Object> rows;
        private final Thread producer;
        private final List<String> header;
        private boolean headerChecked;
        private boolean done;

        private QueueBackedReader(BlockingQueue<Object> rows, Thread producer, List<String> header) {
            this.rows = rows;
            this.producer = producer;
            this.header = header;
        }

        @Override
        public Map<String, String> next() throws IOException {
            if (!headerChecked) {
                List<String> found = trimTrailingBlanks(takeRow());
                if (found == null || !header.equals(found)) {
                    throw new StructureViolation("Header mismatch: expected " + header
                            + " but the sheet's first row has " + found
                            + ". Download the handler's template and keep its column order.");
                }
                headerChecked = true;
            }
            List<String> row = takeRow();
            while (row != null && trimTrailingBlanks(row).isEmpty()) {
                row = takeRow(); // wholly blank rows are skippable noise, as in CSV
            }
            if (row == null) {
                return null;
            }
            Map<String, String> record = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                record.put(header.get(i), i < row.size() ? row.get(i).trim() : "");
            }
            return record;
        }

        @SuppressWarnings("unchecked")
        private List<String> takeRow() throws IOException {
            if (done) {
                return null;
            }
            try {
                Object next = rows.take();
                if (next == END) {
                    done = true;
                    return null;
                }
                if (next instanceof RuntimeException failure) {
                    done = true;
                    throw failure;
                }
                return (List<String>) next;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading XLSX", ex);
            }
        }

        private static List<String> trimTrailingBlanks(List<String> row) {
            if (row == null) {
                return null;
            }
            int end = row.size();
            while (end > 0 && row.get(end - 1).isBlank()) {
                end--;
            }
            return row.subList(0, end).stream().map(String::trim).toList();
        }

        @Override
        public void close() {
            done = true;
            producer.interrupt(); // unblocks a producer stuck on a full queue
        }
    }
}
