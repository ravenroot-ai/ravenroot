package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeMessageBinaryCompatibilityTest {
    @Test
    void everyPublishedConstructorDescriptorStillExistsAndDefaultsToProcess() throws Exception {
        var descriptors = BinaryCompatibility.declaredConstructorDescriptors(NodeMessage.class);
        assertTrue(descriptors.contains(BinaryCompatibility.descriptorOf(SecurityContext.class, UUID.class,
                UUID.class, String.class, Object.class, Map.class)));
        assertTrue(descriptors.contains(BinaryCompatibility.descriptorOf(SecurityContext.class, UUID.class,
                UUID.class, UUID.class, UUID.class, String.class, Object.class, Map.class)));
        assertTrue(descriptors.contains(BinaryCompatibility.descriptorOf(SecurityContext.class, UUID.class,
                UUID.class, UUID.class, UUID.class, Set.class, String.class, Object.class, Map.class)));
        assertTrue(descriptors.contains(BinaryCompatibility.descriptorOf(SecurityContext.class, UUID.class,
                UUID.class, UUID.class, UUID.class, Set.class, String.class, Object.class, Map.class,
                NodeCommand.class)));

        var security = new SecurityContext("request", "tenant", "subject",
                ai.ravenroot.api.security.PrincipalType.USER, "issuer");
        UUID id = UUID.randomUUID();
        assertEquals(NodeCommand.PROCESS, new NodeMessage(security, id, id, "node", "payload", Map.of()).command());
    }
}
