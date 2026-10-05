package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.egress.TrustedNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedNetworkKafkaProfileTest {
    @Test
    void authenticatedNonLoopbackPlaintextRequiresExactAdministratorRule() {
        String key = EnvironmentKafkaProfileResolver.environmentVariableName("t", "p");
        String value = "10.30.1.8:9092;use_all_dns_ips;false;SCRAM-SHA-256;user;secret;client;events;"
                + "audit;trace;false;0;false;none;all;true;3;5;false;2;10;1000;4096;8192";
        assertThrows(SecurityException.class,
                () -> new EnvironmentKafkaProfileResolver(Map.of(key, value)).resolve("t", "p"));

        Map<String, String> environment = new HashMap<>();
        environment.put(key, value);
        environment.put(TrustedNetworkPolicy.ENVIRONMENT_VARIABLE, encoded("""
                {"version":1,"rules":[{"name":"kafka-mesh","protocols":["kafka"],
                "ports":[9092],"hosts":["10.30.1.8"],"addresses":["10.30.0.0/16"],
                "profiles":["t/p"],"allowPlaintext":true}]}
                """));
        assertTrue(new EnvironmentKafkaProfileResolver(environment).resolve("t", "p").isPresent());
    }

    private static String encoded(String json) {
        return Base64.getEncoder().encodeToString(
                json.replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8));
    }
}
