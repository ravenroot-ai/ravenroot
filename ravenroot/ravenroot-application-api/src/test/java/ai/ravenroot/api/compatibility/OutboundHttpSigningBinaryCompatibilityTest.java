package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundHttpSigningBinaryCompatibilityTest {
    @Test
    void requestConstructorPublishedBeforeDynamicSigningStillLinks() throws Exception {
        var shape = new BinaryCompatibility.ConstructorShape("managed HTTP request",
                List.of(URI.class, String.class, Map.class, byte[].class, Duration.class,
                        OutboundCredentialBinding.class),
                List.of("java.net.URI.create(\"https://example.test\")", "\"GET\"",
                        "java.util.Map.of()", "new byte[0]", "java.time.Duration.ofSeconds(1)",
                        "null"));

        assertTrue(BinaryCompatibility.declaredConstructorDescriptors(OutboundHttpRequest.class)
                .contains(shape.descriptor()));
        assertTrue(BinaryCompatibility.linksAgainstCurrent(OutboundHttpRequest.class, shape));
    }
}
