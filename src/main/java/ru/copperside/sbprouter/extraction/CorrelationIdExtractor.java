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

    private static final java.util.Set<String> MESSAGE_TYPES = java.util.Set.of(
            "ReqAuthPay", "AnsAuthPay", "ReqNoticePay", "AnsNoticePay", "ReqPersonalList", "AnsPersonalList");

    /** Returns the {@code stan} attribute of {@code <Document>}, or {@code null} if absent/unparseable. */
    public String extract(byte[] xmlBytes) {
        return extractMessageInfo(xmlBytes).correlationId();
    }

    /**
     * Returns the correlation id (stan of {@code <Document>}) and the message type (local-name of the
     * first application element among {@link #MESSAGE_TYPES}, namespace-agnostic so the GCSvcWS SOAP
     * wrapper is handled). Either field may be {@code null} when absent/unparseable.
     */
    public GcsvcMessageInfo extractMessageInfo(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            return new GcsvcMessageInfo(null, null);
        }
        String stan = null;
        String type = null;
        XMLStreamReader reader = null;
        try {
            reader = XML_INPUT_FACTORY.createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    if (stan == null && "Document".equals(localName)) {
                        stan = reader.getAttributeValue(null, "stan");
                    }
                    if (type == null && MESSAGE_TYPES.contains(localName)) {
                        type = localName;
                    }
                    if (stan != null && type != null) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse GCSvc message info from XML: {}", e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // best-effort close
                }
            }
        }
        return new GcsvcMessageInfo(stan, type);
    }
}
