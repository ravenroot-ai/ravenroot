package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.security.egress.TrustedNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedNetworkAmqpProfileTest {
    @Test
    void malformedProfileIsRejectedBeforeDestinationAuthorization() {
        String key = EnvironmentAmqpProfileResolver.environmentVariableName("t", "p");
        String emptyHost = ";5672;false;/;user;secret;orders;audit;created;updated;trace;responses;"
                + "true;5;60000;2;10;1000;4096;2";
        String invalidPort = "localhost;0;false;/;user;secret;orders;audit;created;updated;trace;responses;"
                + "true;5;60000;2;10;1000;4096;2";

        assertTrue(new EnvironmentAmqpProfileResolver(Map.of(key, emptyHost)).resolve("t", "p").isEmpty());
        assertTrue(new EnvironmentAmqpProfileResolver(Map.of(key, invalidPort)).resolve("t", "p").isEmpty());
    }

    @Test
    void authenticatedNonLoopbackPlaintextRequiresExactAdministratorRule() {
        String key = EnvironmentAmqpProfileResolver.environmentVariableName("t", "p");
        String value = "10.20.1.7;5672;false;/;user;secret;orders;audit;created;updated;trace;responses;"
                + "true;5;60000;2;10;1000;4096;2";
        assertThrows(SecurityException.class,
                () -> new EnvironmentAmqpProfileResolver(Map.of(key, value)).resolve("t", "p"));

        Map<String, String> environment = new HashMap<>();
        environment.put(key, value);
        environment.put(TrustedNetworkPolicy.ENVIRONMENT_VARIABLE, encoded("""
                {"version":1,"rules":[{"name":"amqp-mesh","protocols":["amqp091"],
                "ports":[5672],"hosts":["10.20.1.7"],"addresses":["10.20.0.0/16"],
                "profiles":["t/p"],"allowPlaintext":true}]}
                """));
        assertTrue(new EnvironmentAmqpProfileResolver(environment).resolve("t", "p").isPresent());
    }

    private static String encoded(String json) {
        return Base64.getEncoder().encodeToString(
                json.replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8));
    }
}
