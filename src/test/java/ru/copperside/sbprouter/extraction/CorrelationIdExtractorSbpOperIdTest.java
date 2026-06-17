package ru.copperside.sbprouter.extraction;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdExtractorSbpOperIdTest {

    private final CorrelationIdExtractor extractor = new CorrelationIdExtractor();
    private static byte[] xml(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void correlationIsStan_operationIdIsSbpOperId_typeFromSbpOperType() {
        String x = "<Document stan=\"stan-1\"><GCSvc version=\"1\"><Payment><ReqAuthPay>"
                + "<PayProfile><PNameID>SbpOperId</PNameID><PValue>A614711381</PValue></PayProfile>"
                + "<PayProfile><PNameID>SbpOperation</PNameID><PValue>C2BQRS_Rcv</PValue></PayProfile>"
                + "<PayProfile><PNameID>SbpOperType</PNameID><PValue>C2BQRS</PValue></PayProfile>"
                + "</ReqAuthPay></Payment></GCSvc></Document>";
        GcsvcMessageInfo i = extractor.extractMessageInfo(xml(x));
        assertThat(i.correlationId()).isEqualTo("stan-1");
        assertThat(i.messageType()).isEqualTo("ReqAuthPay");
        assertThat(i.operationId()).isEqualTo("A614711381");
        assertThat(i.sbpOperation()).isEqualTo("C2BQRS_Rcv");
        assertThat(i.operationType()).isEqualTo("C2B");
    }

    @Test
    void operationTypeFallsBackToSbpOperationPrefix_whenNoSbpOperType() {
        String x = "<Document stan=\"s2\"><GCSvc><Payment><ReqNoticePay>"
                + "<PayProfile><PNameID>SbpOperation</PNameID><PValue>B2COther_Snd</PValue></PayProfile>"
                + "</ReqNoticePay></Payment></GCSvc></Document>";
        GcsvcMessageInfo i = extractor.extractMessageInfo(xml(x));
        assertThat(i.operationType()).isEqualTo("B2C");
        assertThat(i.operationId()).isNull();
    }

    @Test
    void supportsDirectElements() {
        String x = "<Document stan=\"s3\"><GCSvc><Payment><ReqAuthPay>"
                + "<SbpOperId>B5170090</SbpOperId><SbpOperType>B2B</SbpOperType></ReqAuthPay></Payment></GCSvc></Document>";
        GcsvcMessageInfo i = extractor.extractMessageInfo(xml(x));
        assertThat(i.operationId()).isEqualTo("B5170090");
        assertThat(i.operationType()).isEqualTo("B2B");
    }

    @Test
    void nullsWhenAbsent_correlationStillStan() {
        String x = "<Document stan=\"s4\"><GCSvc><Payment><ReqNoticePay/></Payment></GCSvc></Document>";
        GcsvcMessageInfo i = extractor.extractMessageInfo(xml(x));
        assertThat(i.correlationId()).isEqualTo("s4");
        assertThat(i.operationId()).isNull();
        assertThat(i.sbpOperation()).isNull();
        assertThat(i.operationType()).isNull();
    }
}
