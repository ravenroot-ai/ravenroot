package ai.ravenroot.api.payload;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The version and schema rules, which are the part of API-01 a client actually has to program to. */
class PayloadEnvelopeTest {

    /** {@code PayloadJson.readEnvelope} takes only {@code byte[]}; see {@code PayloadJsonTest}. */
    private static PayloadEnvelope readEnvelope(String json, PayloadLimits limits) {
        return PayloadJson.readEnvelope(json.getBytes(StandardCharsets.UTF_8), limits);
    }

    @Test
    void theLegacyTextualPayloadIsTheDegenerateScalarAndSaysSo() {
        var envelope = PayloadEnvelope.legacyText("hello");
        assertEquals(PayloadKind.SCALAR, envelope.kind());
        assertEquals(PayloadEnvelope.LEGACY_TEXT_SCHEMA, envelope.schema());
        assertEquals("hello", envelope.toJava());
        assertEquals(PayloadValue.of("hello"), envelope.value());
    }

    @Test
    void anAbsentLegacyPayloadIsEmptyTextRatherThanNull() {
        assertEquals(PayloadValue.of(""), PayloadEnvelope.legacyText(null).value());
    }

    @Test
    void anUnknownContractVersionIsRefusedRatherThanBestEffortInterpreted() {
        var rejection = assertThrows(PayloadException.class, () -> new PayloadEnvelope(
                "ravenroot.payload/2", "s", "1", PayloadKind.SCALAR, PayloadValue.of("x")));
        assertEquals(PayloadException.Reason.UNSUPPORTED_CONTRACT_VERSION, rejection.reason());
    }

    @Test
    void anUnknownMemberIsIgnoredSoTheEnvelopeCanGrowAdditively() {
        String json = "{\"contract\":\"" + PayloadEnvelope.CONTRACT
                + "\",\"aMemberFromTheFuture\":[1,2],\"value\":\"kept\"}";
        assertEquals(PayloadValue.of("kept"),
                readEnvelope(json, PayloadLimits.DEFAULTS).value());
    }

    @Test
    void aDeclaredKindThatDoesNotDescribeTheValueIsRefused() {
        var rejection = assertThrows(PayloadException.class, () -> new PayloadEnvelope(
                PayloadEnvelope.CONTRACT, "s", "1", PayloadKind.MAP, PayloadValue.of("x")));
        assertEquals(PayloadException.Reason.KIND_MISMATCH, rejection.reason());

        String json = "{\"contract\":\"" + PayloadEnvelope.CONTRACT + "\",\"kind\":\"LIST\",\"value\":{}}";
        assertEquals(PayloadException.Reason.KIND_MISMATCH,
                assertThrows(PayloadException.class,
                        () -> readEnvelope(json, PayloadLimits.DEFAULTS)).reason());
    }

    @Test
    void theKindIsDerivedFromTheValueWhenTheCallerDoesNotDeclareOne() {
        assertEquals(PayloadKind.MAP, PayloadEnvelope.of(PayloadValue.map(Map.of())).kind());
        assertEquals(PayloadKind.LIST, PayloadEnvelope.of(PayloadValue.list()).kind());
        assertEquals(PayloadKind.SCALAR, PayloadEnvelope.of(PayloadValue.NULL).kind());
    }

    @Test
    void aSchemaLabelIsATokenRatherThanFreeTextBecauseItIsEchoedAndAudited() {
        assertEquals("urn:example:order.v2+json",
                PayloadEnvelope.of("urn:example:order.v2+json", "2", PayloadValue.NULL).schema());
        for (String hostile : new String[]{"a\" ,\"injected\":\"x", "spaces here", "<script>", "a\nb"}) {
            assertThrows(PayloadException.class,
                    () -> PayloadEnvelope.of(hostile, "1", PayloadValue.NULL),
                    "a hostile schema label was accepted: " + hostile);
        }
        assertEquals(PayloadException.Reason.KEY_TOO_LONG,
                assertThrows(PayloadException.class,
                        () -> PayloadEnvelope.of("x".repeat(129), "1", PayloadValue.NULL)).reason());
    }

    @Test
    void theEnvelopeRoundTripsThroughItsOwnEncoding() {
        var envelope = PayloadEnvelope.of("urn:example:order", "7",
                PayloadValue.map(Map.of("id", PayloadValue.of(1L))));
        var decoded = readEnvelope(envelope.toJson(), PayloadLimits.DEFAULTS);
        assertEquals(envelope, decoded);
    }
}
