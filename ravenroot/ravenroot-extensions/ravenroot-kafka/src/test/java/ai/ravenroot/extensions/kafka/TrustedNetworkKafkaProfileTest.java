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
    void loopbackPlaintextStillRequiresTheExactAdministratorScope() {
        String key = EnvironmentKafkaProfileResolver.environmentVariableName("t", "p");
        String spoofedKey = EnvironmentKafkaProfileResolver.environmentVariableName("t", "spoofed");
        String value = "localhost:9092;use_all_dns_ips;false;PLAIN;user;secret;client;events;"
                + "audit;trace;false;0;false;none;all;true;3;5;false;2;10;1000;4096;8192";
        assertThrows(SecurityException.class,
                () -> new EnvironmentKafkaProfileResolver(Map.of(key, value)).resolve("t", "p"));

        String policy = encoded("""
                {"version":1,"rules":[{"name":"kafka-local","protocols":["kafka"],
                "ports":[9092],"hosts":["localhost"],"addresses":["127.0.0.0/8","::1/128"],
                "profiles":["t/p"],"allowPlaintext":true}]}
                """);
        assertTrue(new EnvironmentKafkaProfileResolver(Map.of(
                key, value, TrustedNetworkPolicy.ENVIRONMENT_VARIABLE, policy)).resolve("t", "p").isPresent());
        assertThrows(SecurityException.class, () -> new EnvironmentKafkaProfileResolver(Map.of(
                spoofedKey, value, TrustedNetworkPolicy.ENVIRONMENT_VARIABLE, policy)).resolve("t", "spoofed"));
    }

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

    @Test
    void consumerPlaintextUsesTheSameScopedAdministratorAdmission() {
        String key = EnvironmentKafkaConsumerProfileResolver.environmentVariableName("t", "reader");
        String value = "10.30.1.8:9092;use_all_dns_ips;false;SCRAM-SHA-256;user;secret;client;reader;group;;"
                + "orders;;trace;cooperative-sticky;earliest;read_committed;1000;100;300000;10000;3000;10;"
                + "1048576;524288;262144;1024;131072;4096;1000;100;1000;3;halt;";
        assertThrows(SecurityException.class,
                () -> new EnvironmentKafkaConsumerProfileResolver(Map.of(key, value)).resolve("t", "reader"));

        Map<String, String> environment = new HashMap<>();
        environment.put(key, value);
        environment.put(TrustedNetworkPolicy.ENVIRONMENT_VARIABLE, encoded("""
                {"version":1,"rules":[{"name":"kafka-consumer-mesh","protocols":["kafka"],
                "ports":[9092],"hosts":["10.30.1.8"],"addresses":["10.30.0.0/16"],
                "profiles":["t/reader"],"allowPlaintext":true}]}
                """));
        assertTrue(new EnvironmentKafkaConsumerProfileResolver(environment).resolve("t", "reader").isPresent());
    }

    private static String encoded(String json) {
        return Base64.getEncoder().encodeToString(
                json.replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8));
    }
}
