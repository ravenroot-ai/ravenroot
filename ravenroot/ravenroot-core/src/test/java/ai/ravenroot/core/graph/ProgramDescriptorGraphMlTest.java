package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Author-owned program content survives GraphML without materialising authority state. */
class ProgramDescriptorGraphMlTest {
    @Test
    void exactSourceLanguageAndSmokePayloadRoundTripWithoutQualificationAuthority() {
        byte[] authored = """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="language" for="node" attr.name="language" attr.type="string"/>
                  <key id="source" for="node" attr.name="source" attr.type="string"/>
                  <key id="testPayload" for="node" attr.name="testPayload" attr.type="string"/>
                  <key id="artifactId" for="node" attr.name="artifactId" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="start"><data key="kind">START</data></node>
                    <node id="program">
                      <data key="kind">BEHAVIOR</data>
                      <data key="behavior">program</data>
                      <data key="language">javascript</data>
                      <data key="source">const value = &quot;&lt;tag&gt;&quot;;
                return value;</data>
                      <data key="testPayload">{&quot;sample&quot;:true}</data>
                      <data key="artifactId">managed-reference</data>
                    </node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="program"/>
                    <edge id="e2" source="program" target="end"/>
                  </graph>
                </graphml>
                """.getBytes(StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();

        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(authored))) {
            var properties = manager.definition().node("program").properties();
            assertEquals("javascript", properties.get("language"));
            assertEquals("const value = \"<tag>\";\nreturn value;", properties.get("source"));
            assertEquals("{\"sample\":true}", properties.get("testPayload"));
            assertEquals("managed-reference", properties.get("artifactId"));
            assertFalse(properties.containsKey("sha256"));
            assertFalse(properties.containsKey("lifecycle"));
            manager.writeGraphMl(output);
        }

        assertArrayEquals(authored, output.toByteArray());
    }
}
