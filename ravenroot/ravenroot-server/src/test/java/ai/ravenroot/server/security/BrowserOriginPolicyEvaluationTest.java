package ai.ravenroot.server.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserOriginPolicyEvaluationTest {
    private final BrowserOriginPolicy policy = new BrowserOriginPolicy(Set.of("https://console.example"));

    @Test
    void acceptsAbsentOrOneExactCanonicalOrigin() {
        assertTrue(policy.evaluate(List.of()).accepted());
        var accepted = policy.evaluate(List.of("https://console.example"));
        assertTrue(accepted.accepted());
        assertEquals("https://console.example", accepted.origin());
    }

    @Test
    void rejectsRepeatedMalformedNullWildcardAndDisallowedOrigins() {
        assertEquals(400, policy.evaluate(List.of("https://console.example", "https://console.example")).status());
        assertEquals(400, policy.evaluate(List.of("https://console.example/path")).status());
        assertEquals(400, policy.evaluate(List.of("null")).status());
        assertEquals(400, policy.evaluate(List.of("*")).status());
        assertEquals(403, policy.evaluate(List.of("https://other.example")).status());
    }
}
