package ru.copperside.sbprouter.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;

/**
 * Minimal, content-agnostic extractor: pulls the SBP correlation id (the {@code stan} attribute of
 * the root {@code <Document>} element) so request and response traffic can be keyed as a pair in
 * Kafka. The router does not otherwise inspect or route on payload content — it is a flat proxy.
 */
@Component
public class CorrelationIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdExtractor.class);

    private static final XMLInputFactory XML_INPUT_FACTORY = XMLInputFactory.newInstance();

    static {
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.IS_COALESCING, true);
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    /** Returns the {@code stan} attribute of {@code <Document>}, or {@code null} if absent/unparseable. */
    public String extract(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            return null;
        }
        XMLStreamReader reader = null;
        try {
            reader = XML_INPUT_FACTORY.createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && "Document".equals(reader.getLocalName())) {
                    // Root element reached — the stan lives here; no need to read further.
                    return reader.getAttributeValue(null, "stan");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse correlation id from XML: {}", e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // best-effort close
                }
            }
        }
        return null;
    }
}
