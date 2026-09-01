package ai.ravenroot.api.error;

import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorEnvelopeTest {

    /** Text a careless call site might lift from an exception, distinctive enough to grep for. */
    private static final String SENTINEL = "internal:/var/secrets/db.pem password=hunter2";

    /** The same idea, shaped to close its own JSON string and open a sibling member. */
    private static final String BREAKOUT = "A\",\"injected\":\"yes";

    private static final Set<String> ALLOWED_KEYS =
            Set.of("contract", "code", "message", "error", "correlationId", "incidentId");

    /** {@code PayloadJson.read} takes only {@code byte[]}; see {@code PayloadJsonTest}. */
    private static PayloadValue read(String json, PayloadLimits limits) {
        return PayloadJson.read(json.getBytes(StandardCharsets.UTF_8), limits);
    }

    /**
     * The redaction guarantee, asserted as the invariant it actually is.
     *
     * <p>The previous version of this test checked the <em>shape</em> of the type: it allowed any
     * public method up to two String parameters and asserted only that constructors had arity 5. Both
     * heuristics were satisfied by the public canonical constructor that {@code public record}
     * generates, so the test passed while {@code new ErrorEnvelope(CONTRACT, code, anyText, id, null)}
     * put arbitrary text straight into {@code message}. A structural claim has to be checked
     * structurally: enumerate every public way in and prove none of them carries caller text through.</p>
     *
     * <p>The {@link PayloadException} overload is admitted by name, as before, because that type's
     * messages are authored by the payload module's own policy — its constructor is package-private
     * there — and the type is the proof.</p>
     */
    @Test
    void noPublicEntryPointCanPlaceCallerSuppliedTextIntoTheEnvelope() {
        for (Constructor<?> constructor : ErrorEnvelope.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()),
                    "a public constructor lets a caller pass exception text as the message: " + constructor);
        }

        List<ErrorEnvelope> reached = reachableWith(SENTINEL);
        assertFalse(reached.isEmpty(), "the reflective sweep exercised no entry point at all");
        for (ErrorEnvelope envelope : reached) {
            assertFalse(envelope.message().contains(SENTINEL),
                    "caller text reached message: " + envelope.message());
            assertFalse(envelope.code().contains(SENTINEL),
                    "caller text reached code: " + envelope.code());
            assertFalse(envelope.toJson().contains(SENTINEL),
                    "caller text reached the response body: " + envelope.toJson());
        }
    }

    /**
     * The envelope is closed by encoding, not only by field list.
     *
     * <p>{@code toJson} escaped {@code message} and nothing else, so a {@code code} containing a quote
     * terminated its own string and introduced a member that was never part of the contract. Parsing
     * the result and comparing the key set is the assertion that catches that for every field at once,
     * including any field added later.</p>
     */
    @Test
    void noFieldCanBreakOutOfTheJsonObject() {
        List<ErrorEnvelope> reached = new ArrayList<>(reachableWith(BREAKOUT));
        reached.addAll(reachableWith(SENTINEL));
        reached.add(ErrorEnvelope.of(ErrorCode.INTERNAL_ERROR, "req-1").withIncident(BREAKOUT));

        for (ErrorEnvelope envelope : reached) {
            String json = envelope.toJson();
            assertFalse(json.contains("\"injected\""), "a field broke out of its string: " + json);

            // A field that escapes its string either adds a member or corrupts the document. Both are
            // the same defect, so the parse failure is reported as one rather than thrown as an error.
            PayloadValue decoded;
            try {
                decoded = read(json, PayloadLimits.DEFAULTS);
            } catch (PayloadException rejection) {
                throw new AssertionError(
                        "the encoded envelope is not a well-formed document (" + rejection.code()
                                + "), so a field terminated its own string: " + json);
            }
            var map = (PayloadValue.MapValue) decoded;
            assertTrue(ALLOWED_KEYS.containsAll(map.entries().keySet()),
                    "the envelope grew a member it does not declare: " + json);
        }
    }

    /**
     * Invokes every public entry point that yields an envelope, feeding {@code hostile} into every
     * String position. Factories taking a {@link PayloadException} are skipped by type, which is the
     * one documented admission.
     */
    private static List<ErrorEnvelope> reachableWith(String hostile) {
        var reached = new ArrayList<ErrorEnvelope>();
        var entryPoints = new ArrayList<Executable>();
        entryPoints.addAll(List.of(ErrorEnvelope.class.getDeclaredConstructors()));
        entryPoints.addAll(List.of(ErrorEnvelope.class.getDeclaredMethods()));

        for (Executable entryPoint : entryPoints) {
            if (!Modifier.isPublic(entryPoint.getModifiers()) || entryPoint.isSynthetic()) {
                continue;
            }
            if (entryPoint instanceof Method method
                    && !ErrorEnvelope.class.equals(method.getReturnType())) {
                continue;
            }
            Class<?>[] parameters = entryPoint.getParameterTypes();
            if (List.of(parameters).contains(PayloadException.class)) {
                continue;
            }
            Object[] arguments = new Object[parameters.length];
            boolean buildable = true;
            for (int index = 0; index < parameters.length; index++) {
                if (parameters[index] == String.class) {
                    arguments[index] = hostile;
                } else if (parameters[index] == ErrorCode.class) {
                    arguments[index] = ErrorCode.INTERNAL_ERROR;
                } else {
                    buildable = false;
                }
            }
            if (!buildable) {
                continue;
            }
            try {
                Object produced = entryPoint instanceof Constructor<?> constructor
                        ? constructor.newInstance(arguments)
                        : ((Method) entryPoint).invoke(
                                ErrorEnvelope.of(ErrorCode.CONFLICT, "req-base"), arguments);
                if (produced instanceof ErrorEnvelope envelope) {
                    reached.add(envelope);
                }
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // An entry point that refuses the hostile value outright is a pass, not a gap.
            }
        }
        return reached;
    }

    @Test
    void theMessageIsAFunctionOfTheCodeAndNothingElse() {
        for (ErrorCode code : ErrorCode.values()) {
            var envelope = ErrorEnvelope.of(code, "req-1");
            assertEquals(code.code(), envelope.code());
            assertEquals(code.message(), envelope.message());
            assertFalse(code.message().isBlank(), code + " has no public message");
        }
    }

    @Test
    void aForgedOrInjectedCorrelationHandleIsReplacedRatherThanRendered() {
        for (String hostile : new String[]{"\" , \"injected\":\"yes", "a\nb", "../../etc/passwd",
                "<script>", "x".repeat(129), "", "   ", null}) {
            var envelope = ErrorEnvelope.of(ErrorCode.INVALID_REQUEST, hostile);
            assertNotEquals(hostile, envelope.correlationId(),
                    "a hostile correlation handle was carried verbatim: " + hostile);
            assertTrue(envelope.correlationId().matches("[A-Za-z0-9._:-]{1,128}"), envelope.correlationId());
            assertFalse(envelope.toJson().contains("injected"), envelope.toJson());
        }
    }

    @Test
    void aWellFormedHandleIsKeptSoTheEnvelopeJoinsToTheAuditRecord() {
        String requestId = "0f2a1b7c-1111-4222-8333-444455556666";
        assertEquals(requestId, ErrorEnvelope.of(ErrorCode.ACCESS_DENIED, requestId).correlationId());
    }

    @Test
    void aServerCodeFromAnotherClosedVocabularyIsCarriedButStillTokenised() {
        assertEquals("ADDRESS_RATE_LIMIT_EXCEEDED", ErrorEnvelope.ofServerCode(
                "ADDRESS_RATE_LIMIT_EXCEEDED", ErrorCode.REQUEST_LIMIT_EXCEEDED, "req-1").code());
        assertEquals(ErrorCode.REQUEST_LIMIT_EXCEEDED.code(), ErrorEnvelope.ofServerCode(
                "not a token\", \"injected\":\"yes", ErrorCode.REQUEST_LIMIT_EXCEEDED, "req-1").code());
    }

    @Test
    void aPayloadRejectionKeepsItsClassificationAndItsIncident() {
        PayloadException rejection = assertThrows(PayloadException.class,
                () -> read("{\"a\":1,\"a\":2}", PayloadLimits.DEFAULTS));
        var envelope = ErrorEnvelope.of(rejection, "req-9");
        assertEquals("PAYLOAD_DUPLICATE_KEY", envelope.code());
        assertEquals(rejection.incidentId(), envelope.incidentId());
        assertEquals(rejection.getMessage(), envelope.message());
    }

    @Test
    void theEncodedEnvelopeIsWellFormedAndCarriesTheLegacyErrorField() {
        String json = ErrorEnvelope.of(ErrorCode.CONFLICT, "req-2").toJson();
        var decoded = read(json, PayloadLimits.DEFAULTS);
        assertEquals(PayloadJson.write(decoded), PayloadJson.write(decoded));
        assertTrue(json.contains("\"error\":\""), "pre-API-01 clients read the error field: " + json);
        assertTrue(json.contains("\"contract\":\"" + ErrorEnvelope.CONTRACT + "\""), json);
    }
}
