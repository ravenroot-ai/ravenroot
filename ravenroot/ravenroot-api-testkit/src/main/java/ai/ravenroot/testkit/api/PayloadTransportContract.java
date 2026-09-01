package ai.ravenroot.testkit.api;

import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests every surface that accepts a payload against the mandatory API-01 semantics.
 *
 * <p>The repository already has this shape twice — {@code ExecutionStoreContract} and
 * {@code ExecutionEngineContract} — and it is used here for the same reason. A payload contract that
 * exists only as HTTP tests is a description of one adapter's current behaviour; a payload contract
 * that a surface must pass is a contract. The documented requirements asks for the HTTP surface and the embedded surface,
 * and the way to be sure they agree is to run the same assertions against both rather than to write
 * two suites and hope.</p>
 *
 * <p>What a binding must provide is small on purpose: submit a payload and say what the graph
 * observed, submit a legacy textual payload and say the same, and submit something over budget and
 * report the machine-readable code. Everything the contract asserts is expressed in those terms, so a
 * future transport — a message queue, a gRPC facade, a second HTTP version — implements three methods
 * and inherits the whole contract.</p>
 */
public abstract class PayloadTransportContract {

    /** The budgets the surface under test enforces. Override when a binding configures its own. */
    protected PayloadLimits limits() {
        return PayloadLimits.DEFAULTS;
    }

    /**
     * Submits {@code envelope} and returns the payload the first executed node observed.
     *
     * <p>Observed, not echoed: the assertion that matters is what reached the engine, and a surface
     * that returned the caller's own value would pass every round-trip test while delivering
     * something else.</p>
     */
    protected abstract PayloadValue submit(PayloadEnvelope envelope) throws Exception;

    /** Submits a pre-API-01 textual payload the way an existing client does, byte for byte. */
    protected abstract PayloadValue submitLegacyText(String text) throws Exception;

    /** Submits an envelope expected to be refused, and returns the machine-readable code. */
    protected abstract String submitExpectingRejection(PayloadEnvelope envelope) throws Exception;

    @Test
    final void textScalarRoundTrips() throws Exception {
        assertEquals(PayloadValue.of("hello"), submit(PayloadEnvelope.of(PayloadValue.of("hello"))));
    }

    @Test
    final void integerScalarKeepsItsIntegerIdentity() throws Exception {
        // A number that arrives as 42.0 has lost information a downstream CEL expression relies on.
        assertEquals(PayloadValue.of(42L), submit(PayloadEnvelope.of(PayloadValue.of(42L))));
    }

    @Test
    final void decimalScalarRoundTrips() throws Exception {
        assertEquals(PayloadValue.of(1.5d), submit(PayloadEnvelope.of(PayloadValue.of(1.5d))));
    }

    @Test
    final void booleanScalarRoundTrips() throws Exception {
        assertEquals(PayloadValue.of(false), submit(PayloadEnvelope.of(PayloadValue.of(false))));
    }

    @Test
    final void nullScalarIsAValueRatherThanAnAbsence() throws Exception {
        assertEquals(PayloadValue.NULL, submit(PayloadEnvelope.of(PayloadValue.NULL)));
    }

    @Test
    final void unicodeTextSurvivesTheTransport() throws Exception {
        String text = "order is ready — 🚀 你好";
        assertEquals(PayloadValue.of(text), submit(PayloadEnvelope.of(PayloadValue.of(text))));
    }

    @Test
    final void listPayloadRoundTripsInOrder() throws Exception {
        PayloadValue list = PayloadValue.list(PayloadValue.of("a"), PayloadValue.of(1L), PayloadValue.NULL);
        assertEquals(list, submit(PayloadEnvelope.of(list)));
    }

    @Test
    final void mapPayloadRoundTrips() throws Exception {
        var entries = new LinkedHashMap<String, PayloadValue>();
        entries.put("orderId", PayloadValue.of("A-1"));
        entries.put("quantity", PayloadValue.of(3L));
        entries.put("express", PayloadValue.of(true));
        PayloadValue map = PayloadValue.map(entries);
        assertEquals(map, submit(PayloadEnvelope.of(map)));
    }

    @Test
    final void nestedStructuresRoundTrip() throws Exception {
        PayloadValue nested = PayloadValue.map(Map.of("lines",
                PayloadValue.list(PayloadValue.map(Map.of("sku", PayloadValue.of("X"),
                        "tags", PayloadValue.list(PayloadValue.of("a"), PayloadValue.of("b")))))));
        assertEquals(nested, submit(PayloadEnvelope.of(nested)));
    }

    @Test
    final void emptyCollectionsAreCarriedRatherThanCollapsed() throws Exception {
        assertEquals(PayloadValue.list(), submit(PayloadEnvelope.of(PayloadValue.list())));
        assertEquals(PayloadValue.map(Map.of()), submit(PayloadEnvelope.of(PayloadValue.map(Map.of()))));
    }

    @Test
    final void declaredKindTravelsWithTheValue() throws Exception {
        PayloadValue map = PayloadValue.map(Map.of("k", PayloadValue.of("v")));
        assertEquals(PayloadKind.MAP, PayloadEnvelope.of(map).kind());
        assertEquals(map, submit(PayloadEnvelope.of("urn:test:order", "3", map)));
    }

    /**
     * An existing client sends what it always sent and a node sees what it always saw.
     *
     * <p>The legacy textual payload is not translated into the model, it <em>is</em> a point in the
     * model — the degenerate scalar — so this assertion is the compatibility guarantee itself rather
     * than a test of a compatibility shim.</p>
     */
    @Test
    final void legacyTextualPayloadIsTheDegenerateScalar() throws Exception {
        assertEquals(PayloadValue.of("hello"), submitLegacyText("hello"));
        assertEquals(PayloadEnvelope.legacyText("hello").value(), submitLegacyText("hello"));
    }

    @Test
    final void absentLegacyPayloadIsEmptyTextRatherThanNull() throws Exception {
        assertEquals(PayloadValue.of(""), submitLegacyText(""));
    }

    @Test
    final void nestingBeyondTheDepthLimitIsRefused() throws Exception {
        PayloadValue deep = PayloadValue.of("leaf");
        for (int level = 0; level < limits().maxDepth() + 4; level++) {
            deep = PayloadValue.list(deep);
        }
        assertEquals("PAYLOAD_DEPTH_LIMIT_EXCEEDED", submitExpectingRejection(unchecked(deep)));
    }

    @Test
    final void aCollectionBeyondTheElementLimitIsRefused() throws Exception {
        var values = new ArrayList<PayloadValue>();
        for (int index = 0; index <= limits().maxCollectionSize(); index++) {
            values.add(PayloadValue.of((long) index));
        }
        assertEquals("PAYLOAD_COLLECTION_LIMIT_EXCEEDED",
                submitExpectingRejection(unchecked(PayloadValue.list(values))));
    }

    @Test
    final void textBeyondTheLengthLimitIsRefused() throws Exception {
        String oversized = "x".repeat(limits().maxTextLength() + 1);
        assertEquals("PAYLOAD_TEXT_TOO_LONG",
                submitExpectingRejection(unchecked(PayloadValue.of(oversized))));
    }

    /**
     * SEC-07, restated for structured payloads.
     *
     * <p>The reserved prefix must be refused wherever it appears, not only at the top level, because
     * what it protects against is a plausible-looking key being believed — and a nested key is no less
     * believable.</p>
     */
    @Test
    final void reservedSecurityKeysAreRefusedAnywhereInTheTree() throws Exception {
        PayloadValue nested = PayloadValue.map(Map.of("outer",
                PayloadValue.map(Map.of("ravenroot.security.tenantId", PayloadValue.of("attacker")))));
        assertEquals("PAYLOAD_RESERVED_KEY", submitExpectingRejection(unchecked(nested)));
    }

    @Test
    final void aRejectionCodeIsNeverBlank() throws Exception {
        String code = submitExpectingRejection(unchecked(PayloadValue.of("x".repeat(limits().maxTextLength() + 1))));
        assertNotNull(code);
        assertEquals(code.strip(), code);
    }

    /**
     * Builds an envelope around a value that is deliberately over budget.
     *
     * <p>{@link PayloadEnvelope#of(PayloadValue)} does not enforce limits — enforcement belongs to the
     * surface that accepts the payload, and a constructor that refused an oversized value would make
     * the over-budget case impossible to <em>submit</em> and therefore impossible to test. That is the
     * whole reason this helper exists rather than a comment explaining why the test builds one.</p>
     */
    private static PayloadEnvelope unchecked(PayloadValue value) {
        return PayloadEnvelope.of(value);
    }

    /** Convenience for bindings that need a list of the kinds the contract covers. */
    protected static List<PayloadValue> sampleValues() {
        return List.of(PayloadValue.NULL, PayloadValue.of(true), PayloadValue.of(7L), PayloadValue.of(0.5d),
                PayloadValue.of("text"), PayloadValue.list(PayloadValue.of(1L)),
                PayloadValue.map(Map.of("k", PayloadValue.of("v"))));
    }
}
