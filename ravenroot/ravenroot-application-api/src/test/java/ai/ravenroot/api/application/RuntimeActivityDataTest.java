package ai.ravenroot.api.application;

import ai.ravenroot.api.payload.PayloadJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeActivityDataTest {
    private static final String SENTINEL = "s3nt1nel-value";

    @Test
    void preservesUsefulTextAroundAnAssignedPasswordAndReportsRedaction() {
        var projected = RuntimeActivityData.message(
                "SMTP rejected alice: password=" + SENTINEL + "; retry disabled");

        assertTrue(projected.value().contains("SMTP rejected alice"));
        assertTrue(projected.value().contains("retry disabled"));
        assertFalse(projected.value().contains(SENTINEL));
        assertTrue(projected.value().contains(RuntimeActivityData.REDACTION_MARKER));
        assertTrue(projected.redacted());
        assertFalse(projected.truncated());
    }

    @Test
    void redactsTheFiniteHighConfidenceCredentialVocabularyWithoutBlanketSuppression() {
        List<String> fixtures = List.of(
                "Authorization: Bearer " + SENTINEL,
                "Proxy-Authorization: Basic " + SENTINEL,
                "token=" + SENTINEL,
                "credential=" + SENTINEL,
                "api_key=" + SENTINEL,
                "accessToken=" + SENTINEL,
                "client_secret=" + SENTINEL,
                "{\"password\":\"" + SENTINEL + "\"}",
                "db_password=" + SENTINEL,
                "Cookie: session=" + SENTINEL + "; csrf=" + SENTINEL + "-2; retry disabled",
                "Set-Cookie: session=" + SENTINEL,
                "eyJabcdefgh.ijklmnop.qrstuvwx",
                "-----BEGIN PRIVATE KEY-----\n" + SENTINEL + "\n-----END PRIVATE KEY-----");

        for (String fixture : fixtures) {
            var projected = RuntimeActivityData.message(fixture);
            assertFalse(projected.value().contains(SENTINEL), fixture + " -> " + projected.value());
            assertTrue(projected.redacted(), fixture);
        }
        var control = RuntimeActivityData.message("job tokenization id=customer-42 credentialRef=mail-prod");
        assertTrue(control.value().contains("customer-42"));
        assertTrue(control.value().contains("credentialRef=mail-prod"));
        assertFalse(control.redacted());
        var cookie = RuntimeActivityData.message(
                "Cookie: session=" + SENTINEL + "; csrf=" + SENTINEL + "-2; retry disabled");
        assertFalse(cookie.value().contains(SENTINEL));
        assertTrue(cookie.value().contains("retry disabled"));
    }

    @Test
    void redactsCompleteSingleAndDoubleQuotedAssignedValuesContainingSpaces() {
        for (String fixture : List.of(
                "{\"password\":\"alpha beta gamma\"}",
                "password='alpha beta gamma' retry disabled",
                "client_secret = \"alpha beta gamma\"; retry disabled")) {
            var projected = RuntimeActivityData.message(fixture);

            assertFalse(projected.value().contains("alpha"), fixture + " -> " + projected.value());
            assertFalse(projected.value().contains("beta gamma"), fixture + " -> " + projected.value());
            assertTrue(projected.value().contains(RuntimeActivityData.REDACTION_MARKER), projected.value());
            assertTrue(projected.redacted(), fixture);
        }

        var control = RuntimeActivityData.message(
                "credentialRef='mail production' tokenization=\"alpha beta gamma\"");
        assertTrue(control.value().contains("mail production"));
        assertTrue(control.value().contains("alpha beta gamma"));
        assertFalse(control.redacted());
    }

    @Test
    void redactsBeforeUtf8BoundingAndReportsBothOperationsWithoutSplittingEmoji() {
        String value = "x".repeat(RuntimeActivityData.MAX_MESSAGE_UTF8_BYTES - 40)
                + " password=" + SENTINEL + " tail-after-secret " + "🙂".repeat(20);

        var projected = RuntimeActivityData.message(value);

        assertFalse(projected.value().contains(SENTINEL));
        assertTrue(projected.value().contains(RuntimeActivityData.REDACTION_MARKER));
        assertTrue(projected.value().endsWith(RuntimeActivityData.TRUNCATION_MARKER));
        assertTrue(projected.redacted());
        assertTrue(projected.truncated());
        assertTrue(projected.value().getBytes(StandardCharsets.UTF_8).length
                <= RuntimeActivityData.MAX_MESSAGE_UTF8_BYTES);
        assertFalse(projected.value().contains("\ufffd"));
        assertFalse(hasLoneSurrogate(projected.value()));
        assertFalse(hasLoneSurrogate(RuntimeActivityData.message("bad \ud800 diagnostic").value()));
    }

    @Test
    void authoredMarkersDoNotForgeOperationFlags() {
        var projected = RuntimeActivityData.message(
                "literal " + RuntimeActivityData.TRUNCATION_MARKER + " " + RuntimeActivityData.REDACTION_MARKER);

        assertFalse(projected.truncated());
        assertFalse(projected.redacted());
    }

    @Test
    void structuredOutputRedactsNestedSecretsAndDeclaresEveryLimit() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("credentialRef", "mail-prod");
        nested.put("db_password", SENTINEL);
        nested.put("message", "ok");
        Object deep = "cut";
        for (int level = 0; level < RuntimeActivityData.MAX_OUTPUT_DEPTH + 1; level++) {
            deep = Map.of("level" + level, deep);
        }
        nested.put("deep", deep);
        nested.put("many", java.util.stream.IntStream.range(0, 40).boxed().toList());
        nested.put("long", "🙂".repeat(RuntimeActivityData.MAX_TEXT_UTF8_BYTES));

        var projected = RuntimeActivityData.output(nested);
        String json = PayloadJson.write(projected.value());

        assertFalse(json.contains(SENTINEL));
        assertTrue(json.contains(RuntimeActivityData.REDACTION_MARKER));
        assertTrue(json.contains("credentialRef"));
        assertTrue(json.contains("mail-prod"));
        assertTrue(json.contains("ravenroot:truncated"));
        assertTrue(projected.redacted());
        assertTrue(projected.truncated());
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= RuntimeActivityData.MAX_OUTPUT_UTF8_BYTES + 2);
    }

    @Test
    void overallOutputLimitKeepsABoundedPrefixAndAnExplicitMarker() {
        Map<String, Object> large = new LinkedHashMap<>();
        for (int index = 0; index < RuntimeActivityData.MAX_COLLECTION_SIZE; index++) {
            large.put("field-" + index, "value-" + index + "-" + "x".repeat(2_000));
        }

        var projected = RuntimeActivityData.output(large);
        String json = PayloadJson.write(projected.value());

        assertTrue(projected.truncated());
        assertTrue(json.contains("field-0"));
        assertTrue(json.contains(RuntimeActivityData.TRUNCATION_MARKER));
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= RuntimeActivityData.MAX_OUTPUT_UTF8_BYTES);
    }

    @Test
    void overallOutputLimitKeepsLateRedactionAndTruncationMarkersTogether() {
        Map<String, Object> large = new LinkedHashMap<>();
        for (int index = 0; index <= 30; index++) {
            large.put("a%02d".formatted(index), "x".repeat(1_000));
        }
        large.put("zz_password", SENTINEL);

        var projected = RuntimeActivityData.output(large);
        String json = PayloadJson.write(projected.value());

        assertFalse(json.contains(SENTINEL), json);
        assertTrue(projected.redacted(), json);
        assertTrue(projected.truncated(), json);
        assertTrue(json.contains(RuntimeActivityData.REDACTION_MARKER), json);
        assertTrue(json.contains(RuntimeActivityData.TRUNCATION_MARKER), json);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= RuntimeActivityData.MAX_OUTPUT_UTF8_BYTES,
                () -> "wire bytes=" + json.getBytes(StandardCharsets.UTF_8).length);
    }

    private static boolean hasLoneSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
