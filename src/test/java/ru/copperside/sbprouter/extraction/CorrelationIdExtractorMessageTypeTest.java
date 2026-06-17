package ru.copperside.sbprouter.extraction;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdExtractorMessageTypeTest {

    private final CorrelationIdExtractor extractor = new CorrelationIdExtractor();

    private static byte[] xml(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void detectsReqAuthPayAndStan() {
        GcsvcMessageInfo info = extractor.extractMessageInfo(xml(
                "<Document stan=\"s1\"><GCSvc version=\"1\"><Payment><ReqAuthPay/></Payment></GCSvc></Document>"));
        assertThat(info.correlationId()).isEqualTo("s1");
        assertThat(info.messageType()).isEqualTo("ReqAuthPay");
    }

    @Test
    void detectsReqNoticePay() {
        GcsvcMessageInfo info = extractor.extractMessageInfo(xml(
                "<Document stan=\"s2\"><GCSvc><Payment><ReqNoticePay/></Payment></GCSvc></Document>"));
        assertThat(info.messageType()).isEqualTo("ReqNoticePay");
    }

    @Test
    void detectsSoapWrappedReqAuthPayByLocalName() {
        GcsvcMessageInfo info = extractor.extractMessageInfo(xml(
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "xmlns:gc=\"urn:GCSvc/types\"><soapenv:Body>"
                + "<gc:ReqAuthPay stan=\"s3\"/></soapenv:Body></soapenv:Envelope>"));
        assertThat(info.messageType()).isEqualTo("ReqAuthPay");
    }

    @Test
    void nullTypeForUnknownAndBackCompatExtract() {
        byte[] unknown = xml("<Document stan=\"s4\"><GCSvc><Payment><Whatever/></Payment></GCSvc></Document>");
        assertThat(extractor.extractMessageInfo(unknown).messageType()).isNull();
        assertThat(extractor.extract(unknown)).isEqualTo("s4");
    }
}
