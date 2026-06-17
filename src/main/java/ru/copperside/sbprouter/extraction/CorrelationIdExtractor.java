package ru.copperside.sbprouter.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;

/**
 * Minimal extractor for the SBP correlation id and message type. The correlation id keys the
 * request/response traffic pair in Kafka; it is read once from the request and applied to both
 * events. Preference order: the {@code SbpOperId} name-value param ({@code <PNameID>SbpOperId</PNameID>
 * <PValue>...</PValue>} in {@code PayProfile}/{@code AdditionInfo}, or a direct {@code <SbpOperId>}
 * element), then the {@code stan} attribute of {@code <Document>}, then {@code null} (the publisher
 * falls back to the generated txId). The router does not otherwise route on payload content.
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

    /** Returns the correlation id (SbpOperId, else stan), or {@code null} if absent/unparseable. */
    public String extract(byte[] xmlBytes) {
        return extractMessageInfo(xmlBytes).correlationId();
    }

    /**
     * Returns the correlation id and the message type. The correlation id is the {@code SbpOperId}
     * (name-value param or direct element) when present, otherwise the {@code stan} attribute of
     * {@code <Document>}. The message type is the local-name of the first application element among
     * {@link #MESSAGE_TYPES} (namespace-agnostic so the GCSvcWS SOAP wrapper is handled). Either
     * field may be {@code null} when absent/unparseable.
     */
    public GcsvcMessageInfo extractMessageInfo(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            return new GcsvcMessageInfo(null, null);
        }
        String stan = null;
        String type = null;
        String sbpOperId = null;
        String currentTag = null;   // local-name of the element whose text we are reading
        String pendingPName = null; // text of the most recent <PNameID> (to match its sibling <PValue>)
        XMLStreamReader reader = null;
        try {
            reader = XML_INPUT_FACTORY.createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentTag = reader.getLocalName();
                    if (stan == null && "Document".equals(currentTag)) {
                        stan = reader.getAttributeValue(null, "stan");
                    }
                    if (type == null && MESSAGE_TYPES.contains(currentTag)) {
                        type = currentTag;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && sbpOperId == null) {
                    String text = reader.getText();
                    if ("PNameID".equals(currentTag)) {
                        pendingPName = text == null ? null : text.trim();
                    } else if ("PValue".equals(currentTag) && "SbpOperId".equals(pendingPName)) {
                        sbpOperId = text == null ? null : text.trim();
                    } else if ("SbpOperId".equals(currentTag)) {
                        sbpOperId = text == null ? null : text.trim();
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("PValue".equals(reader.getLocalName())) {
                        pendingPName = null;
                    }
                    currentTag = null;
                }
                if (stan != null && type != null && sbpOperId != null) {
                    break;
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
        String correlationId = (sbpOperId != null && !sbpOperId.isBlank()) ? sbpOperId : stan;
        return new GcsvcMessageInfo(correlationId, type);
    }
}
