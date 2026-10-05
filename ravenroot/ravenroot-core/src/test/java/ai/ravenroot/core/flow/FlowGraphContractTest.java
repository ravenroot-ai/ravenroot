package ai.ravenroot.core.flow;

import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowGraphContractTest {
    @Test
    void absentCallableDefaultsTrueAndExplicitFalseRefusesOnlyCallAdmission() {
        assertEquals(Map.of("value", 1L), FlowGraphContract.read(graph(Map.of()))
                .input(Map.of("value", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> FlowGraphContract.read(graph(Map.of("callable", "false"))));
        assertThrows(IllegalArgumentException.class,
                () -> FlowGraphContract.read(graph(Map.of("callable", "sometimes"))));
    }

    @Test
    void optionalSchemasUnwrapMatchingEnvelopesAndRefuseInvalidInputOrOutput() {
        var contract = FlowGraphContract.read(graph(Map.of(
                "call.input.schema", "example.input", "call.input.schemaVersion", "2",
                "call.input.kind", "MAP", "call.output.schema", "example.output",
                "call.output.schemaVersion", "1", "call.output.kind", "SCALAR")));
        Object input = encoded(PayloadEnvelope.of("example.input", "2",
                PayloadValue.map(Map.of("name", PayloadValue.of("Ada")))));
        Object output = encoded(PayloadEnvelope.of("example.output", "1", PayloadValue.of("ok")));
        assertEquals(Map.of("name", "Ada"), contract.input(input));
        assertEquals("ok", contract.output(output));
        assertThrows(IllegalArgumentException.class, () -> contract.input(Map.of("name", "Ada")));
        assertThrows(IllegalArgumentException.class, () -> contract.output(
                encoded(PayloadEnvelope.of("example.output", "2", PayloadValue.of("ok")))));
    }

    @Test
    void partialSchemaDeclarationIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> FlowGraphContract.read(
                graph(Map.of("call.input.schema", "example.input"))));
    }

    private static byte[] graph(Map<String, String> properties) {
        StringBuilder keys = new StringBuilder();
        StringBuilder data = new StringBuilder();
        int index = 0;
        for (var entry : properties.entrySet()) {
            String id = "g" + index++;
            keys.append("<key id=\"").append(id).append("\" for=\"graph\" attr.name=\"")
                    .append(entry.getKey()).append("\" attr.type=\"string\"/>\n");
            data.append("<data key=\"").append(id).append("\">").append(entry.getValue())
                    .append("</data>\n");
        }
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                """ + keys + "<graph id=\"flow\" edgedefault=\"directed\">\n" + data + """
                  <node id="start"><data key="kind">START</data></node>
                  <node id="end"><data key="kind">END</data></node>
                  <edge id="e" source="start" target="end"/>
                </graph></graphml>
                """).getBytes(StandardCharsets.UTF_8);
    }

    private static Object encoded(PayloadEnvelope envelope) {
        return PayloadJson.read(envelope.toJson().getBytes(StandardCharsets.UTF_8), PayloadLimits.DEFAULTS).toJava();
    }
}
