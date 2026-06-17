package ru.copperside.sbprouter.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;

/**
 * Extractor for GCSvc message facts used by the proxy. The {@code correlationId} is the
 * {@code stan} attribute of {@code <Document>} (per-message pairing key for Kafka).
 * {@code operationId} is {@code SbpOperId} (per-operation grouping key).
 * {@code sbpOperation} and {@code operationType} (coarse {@code C2B/B2C/B2B}) are also extracted.
 * Name-value params ({@code <PNameID>NAME</PNameID><PValue>VALUE</PValue>} in
 * {@code PayProfile}/{@code AdditionInfo}) and direct elements are both supported.
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

    private static final java.util.Set<String> WANTED_PARAMS =
            java.util.Set.of("SbpOperId", "SbpOperation", "SbpOperType");

    /** Returns the correlation id (stan), or {@code null} if absent/unparseable. */
    public String extract(byte[] xmlBytes) {
        return extractMessageInfo(xmlBytes).correlationId();
    }

    /**
     * Extracts from a GCSvc request: {@code correlationId} = the {@code stan} attribute of
     * {@code <Document>} (per-message pairing key); {@code messageType} (first whitelisted element,
     * namespace-agnostic); {@code operationId} = {@code SbpOperId}; {@code sbpOperation} =
     * {@code SbpOperation}; {@code operationType} = coarse {@code C2B/B2C/B2B} from {@code SbpOperType}
     * (fallback {@code SbpOperation} prefix). Name-value params ({@code PNameID}/{@code PValue}) and
     * direct elements are both supported. Any field may be {@code null}.
     */
    public GcsvcMessageInfo extractMessageInfo(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            return new GcsvcMessageInfo(null, null, null, null, null);
        }
        String stan = null;
        String type = null;
        java.util.Map<String, String> params = new java.util.HashMap<>();
        String currentTag = null;
        String pendingPName = null;
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
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    String text = reader.getText();
                    String value = text == null ? null : text.trim();
                    if ("PNameID".equals(currentTag)) {
                        pendingPName = value;
                    } else if ("PValue".equals(currentTag) && pendingPName != null
                            && WANTED_PARAMS.contains(pendingPName)) {
                        params.putIfAbsent(pendingPName, value);
                    } else if (WANTED_PARAMS.contains(currentTag)) {
                        params.putIfAbsent(currentTag, value);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("PValue".equals(reader.getLocalName())) {
                        pendingPName = null;
                    }
                    currentTag = null;
                }
                if (stan != null && type != null && params.size() == WANTED_PARAMS.size()) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse GCSvc message info from XML: {}", e.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) { /* best-effort */ }
            }
        }
        String operationId = params.get("SbpOperId");
        String sbpOperation = params.get("SbpOperation");
        String operationType = coarseType(params.get("SbpOperType"), sbpOperation);
        return new GcsvcMessageInfo(stan, type, operationId, sbpOperation, operationType);
    }

    /** Coarse operation type from SbpOperType (preferred) or SbpOperation prefix. */
    private static String coarseType(String sbpOperType, String sbpOperation) {
        String src = (sbpOperType != null && !sbpOperType.isBlank()) ? sbpOperType : sbpOperation;
        if (src == null) {
            return null;
        }
        if (src.startsWith("C2B")) return "C2B";
        if (src.startsWith("B2C")) return "B2C";
        if (src.startsWith("B2B")) return "B2B";
        return null;
    }
}
