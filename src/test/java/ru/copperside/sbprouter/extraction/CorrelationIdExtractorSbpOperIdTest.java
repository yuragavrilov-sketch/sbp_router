package ru.copperside.sbprouter.extraction;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdExtractorSbpOperIdTest {

    private final CorrelationIdExtractor extractor = new CorrelationIdExtractor();

    private static byte[] xml(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void usesSbpOperIdNameValueAsCorrelationId() {
        String x = "<Document stan=\"stan-1\"><GCSvc version=\"1\"><Payment><ReqAuthPay>"
                + "<SenderId>snd-1</SenderId>"
                + "<PayProfile><PNameID>Pay.PayMethod</PNameID><PValue>sbp</PValue></PayProfile>"
                + "<PayProfile><PNameID>SbpOperId</PNameID><PValue>A6147113812500010B10200011770703</PValue></PayProfile>"
                + "</ReqAuthPay></Payment></GCSvc></Document>";
        GcsvcMessageInfo info = extractor.extractMessageInfo(xml(x));
        assertThat(info.correlationId()).isEqualTo("A6147113812500010B10200011770703");
        assertThat(info.messageType()).isEqualTo("ReqAuthPay");
    }

    @Test
    void usesDirectSbpOperIdElement() {
        String x = "<Document stan=\"stan-2\"><GCSvc><Payment><ReqAuthPay>"
                + "<SbpOperId>B5170090940170010000120011530503</SbpOperId></ReqAuthPay></Payment></GCSvc></Document>";
        assertThat(extractor.extractMessageInfo(xml(x)).correlationId())
                .isEqualTo("B5170090940170010000120011530503");
    }

    @Test
    void fallsBackToStanWhenNoSbpOperId() {
        String x = "<Document stan=\"stan-3\"><GCSvc><Payment><ReqNoticePay/></Payment></GCSvc></Document>";
        assertThat(extractor.extractMessageInfo(xml(x)).correlationId()).isEqualTo("stan-3");
    }

    @Test
    void nullWhenNeitherSbpOperIdNorStan() {
        String x = "<Document><GCSvc><Payment><ReqNoticePay/></Payment></GCSvc></Document>";
        assertThat(extractor.extractMessageInfo(xml(x)).correlationId()).isNull();
    }

    @Test
    void ignoresOtherNameValueParams() {
        String x = "<Document stan=\"stan-4\"><GCSvc><Payment><ReqAuthPay>"
                + "<PayProfile><PNameID>Tran.TermName</PNameID><PValue>SB01133</PValue></PayProfile>"
                + "</ReqAuthPay></Payment></GCSvc></Document>";
        // No SbpOperId present -> falls back to stan, must NOT pick up the unrelated PValue.
        assertThat(extractor.extractMessageInfo(xml(x)).correlationId()).isEqualTo("stan-4");
    }
}
