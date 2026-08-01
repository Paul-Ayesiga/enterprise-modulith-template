package ug.co.smsone.exchange.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

/**
 * Record-oriented XML over StAX — pull-parsed, so it streams like every other codec. The shape is
 * fixed and boring on purpose: {@code <records><record><email>…</email>…</record></records>}, one
 * child element per header column. Unknown child elements are ignored; missing ones read as empty.
 * External entities and DTDs are disabled — a tenant-uploaded file must not be an XXE vector.
 */
@Component
class XmlCodec implements FormatCodec {

    static final String ROOT = "records";
    static final String RECORD = "record";

    @Override
    public String id() {
        return "XML";
    }

    @Override
    public String contentType() {
        return "application/xml";
    }

    @Override
    public String fileExtension() {
        return "xml";
    }

    @Override
    public RecordReader reader(InputStream in, List<String> header) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader;
        try {
            reader = factory.createXMLStreamReader(in);
            // Position on the root and verify the document's shape before any record is read.
            if (advanceToElement(reader) == null || !ROOT.equals(reader.getLocalName())) {
                throw new StructureViolation("The XML root element must be <" + ROOT + ">.");
            }
        } catch (XMLStreamException ex) {
            throw new StructureViolation("The file is not well-formed XML.");
        }
        return new RecordReader() {
            @Override
            public Map<String, String> next() throws IOException {
                try {
                    while (reader.hasNext()) {
                        int event = reader.next();
                        if (event == XMLStreamConstants.START_ELEMENT) {
                            if (!RECORD.equals(reader.getLocalName())) {
                                throw new StructureViolation("Only <" + RECORD + "> elements may "
                                        + "appear under <" + ROOT + ">; found <"
                                        + reader.getLocalName() + ">.");
                            }
                            return readRecord();
                        }
                        if (event == XMLStreamConstants.END_ELEMENT
                                && ROOT.equals(reader.getLocalName())) {
                            return null;
                        }
                    }
                    return null;
                } catch (XMLStreamException ex) {
                    throw new StructureViolation("The file is not well-formed XML.");
                }
            }

            private Map<String, String> readRecord() throws XMLStreamException {
                Map<String, String> values = new LinkedHashMap<>();
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        values.put(reader.getLocalName(), reader.getElementText().trim());
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && RECORD.equals(reader.getLocalName())) {
                        break;
                    }
                }
                Map<String, String> record = new LinkedHashMap<>();
                for (String column : header) {
                    record.put(column, values.getOrDefault(column, ""));
                }
                return record;
            }

            @Override
            public void close() throws IOException {
                try {
                    reader.close();
                } catch (XMLStreamException ex) {
                    throw new IOException(ex);
                }
            }
        };
    }

    @Override
    public RecordSink writer(OutputStream out, List<String> header) throws IOException {
        try {
            XMLStreamWriter writer = XMLOutputFactory.newFactory()
                    .createXMLStreamWriter(out, "UTF-8");
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement(ROOT);
            return new RecordSink() {
                @Override
                public void write(Map<String, String> record) throws IOException {
                    try {
                        writer.writeStartElement(RECORD);
                        for (String column : header) {
                            writer.writeStartElement(column);
                            writer.writeCharacters(record.getOrDefault(column, ""));
                            writer.writeEndElement();
                        }
                        writer.writeEndElement();
                    } catch (XMLStreamException ex) {
                        throw new IOException(ex);
                    }
                }

                @Override
                public void close() throws IOException {
                    try {
                        writer.writeEndElement();
                        writer.writeEndDocument();
                        writer.close();
                    } catch (XMLStreamException ex) {
                        throw new IOException(ex);
                    }
                }
            };
        } catch (XMLStreamException ex) {
            throw new IOException(ex);
        }
    }

    private static Integer advanceToElement(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                return event;
            }
        }
        return null;
    }
}
