package ai.ravenroot.server.palette;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NodePaletteWireTest {
    @Test
    void sanitizesBehaviorPropertiesFromTheDescriptorAllowlist() {
        var descriptor = new NodeTypeDescriptor("probe", "Probe", "Test", "", "actor", false,
                List.of(
                        NodePropertyDescriptor.optional("safe", "Safe", NodePropertyType.STRING, "", ""),
                        NodePropertyDescriptor.required("secret", "Secret", NodePropertyType.SECRET_REFERENCE, ""),
                        new NodePropertyDescriptor("adapter", "Adapter", NodePropertyType.STRING, true,
                                "", "", List.of(), true),
                        NodePropertyDescriptor.optional("workspace", "Workspace",
                                NodePropertyType.WORKSPACE_REFERENCE, "", "")), Set.of());
        String body = """
                {"paletteId":"65f0b3ef-8768-4b10-a97d-15555b164a38","name":"Reusable",
                 "node":{"name":"Probe","kind":"BEHAVIOR","behavior":"probe",
                 "nodeType":"agent","classname":"example.Probe",
                 "properties":{"safe":"kept","secret":"raw-secret","adapter":"server-authority",
                 "workspace":"worker-1","password":"arbitrary-secret","host":"evil.example"}}}
                """;

        var saved = NodePaletteWire.readCreateTemplate(body.getBytes(StandardCharsets.UTF_8), List.of(descriptor));

        assertEquals(java.util.Map.of("safe", "kept", "workspace", "worker-1"), saved.node().properties());
        assertEquals("agent", saved.node().nodeType());
        assertEquals("example.Probe", saved.node().classname());
        assertEquals(List.of("workspace"), saved.node().workspaceReferences());
        String payload = NodePaletteWire.payload(saved.node());
        assertFalse(payload.contains("raw-secret"));
        assertFalse(payload.contains("server-authority"));
        assertFalse(payload.contains("evil.example"));
        assertTrue(payload.contains("worker-1"));
    }

    @Test
    void structuralNodesKeepOnlyExplicitStructuralProperties() {
        String body = """
                {"paletteId":"65f0b3ef-8768-4b10-a97d-15555b164a38","name":"Join",
                 "node":{"name":"Join","kind":"PASSTHROUGH","properties":{
                 "joinPolicy":"all","joinTimeout":"1000","credential":"must-drop"}}}
                """;
        var saved = NodePaletteWire.readCreateTemplate(body.getBytes(StandardCharsets.UTF_8), List.of());
        assertEquals(java.util.Map.of("joinPolicy", "all", "joinTimeout", "1000"),
                saved.node().properties());
    }

    @Test
    void unavailableBehaviorsAndUnknownNodeFieldsFailClosed() {
        String unavailable = """
                {"paletteId":"65f0b3ef-8768-4b10-a97d-15555b164a38","name":"Missing",
                 "node":{"name":"Missing","kind":"BEHAVIOR","behavior":"missing.plugin"}}
                """;
        assertThrows(IllegalArgumentException.class, () -> NodePaletteWire.readCreateTemplate(
                unavailable.getBytes(StandardCharsets.UTF_8), List.of()));

        String sourceIdentity = """
                {"paletteId":"65f0b3ef-8768-4b10-a97d-15555b164a38","name":"Unsafe",
                 "node":{"id":"source-node","name":"Start","kind":"START"}}
                """;
        assertThrows(IllegalArgumentException.class, () -> NodePaletteWire.readCreateTemplate(
                sourceIdentity.getBytes(StandardCharsets.UTF_8), List.of()));
    }
}
