package ai.ravenroot.api.programming;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ProgramTestPayloadTest {
    @Test
    void absentDefaultsAndOnlyStrictJsonBecomesStructured() {
        assertEquals("test payload", ProgramTestPayload.parse(null));
        assertEquals(Map.of("value", 3L), ProgramTestPayload.parse("{\"value\":3}"));
        assertEquals("{value:3}", ProgramTestPayload.parse("{value:3}"));
        assertEquals(" true trailing", ProgramTestPayload.parse(" true trailing"));
    }

    @Test
    void payloadIdentityIsSeparateFromSourceIdentityAndMutationSensitive() {
        String payload = ProgramTestPayload.sha256(Map.of("value", 3L));
        assertEquals(payload, ProgramTestPayload.sha256(Map.of("value", 3L)));
        assertNotEquals(payload, ProgramTestPayload.sha256(Map.of("value", 4L)));
        assertNotEquals(payload, ProgramArtifactIdentity.sha256("javascript", "return 3"));
    }
}
