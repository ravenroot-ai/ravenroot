package ai.ravenroot.extensions.kafka;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class KafkaGraphMlRoundTripTest {
    @Test void roundTripsOnlyOpaqueProfileAndTightening() throws Exception {
        Map<String,Object> properties=Map.of("clusterProfile","production-orders","topic","orders","timeoutMs","750","maxConcurrency","2","maxRecordBytes","2048","correlationId","graph-correlation");
        var definition=new GraphDefinition(List.of(GraphNode.start("start"),new GraphNode("produce",NodeKind.BEHAVIOR,KafkaProduceNodeBehavior.BEHAVIOR,properties),GraphNode.error("error"), GraphNode.end("end")),List.of());
        byte[] xml;try(var graph=GraphManager.from(definition);var out=new ByteArrayOutputStream()){graph.writeGraphMl(out);xml=out.toByteArray();}
        String text=new String(xml,StandardCharsets.UTF_8);assertFalse(text.contains(KafkaTestSupport.SECRET));assertFalse(text.contains("broker.example.test"));assertFalse(text.matches("(?s).*(bootstrap|password|credentialRef|sasl|security.protocol).*"));
        try(var reread=GraphManager.readGraphMl(new ByteArrayInputStream(xml))){var node=reread.definition().node("produce");assertEquals(KafkaProduceNodeBehavior.BEHAVIOR,node.behavior());assertEquals(properties,node.properties());}
    }

    @Test void consumeRoundTripsOnlyOpaqueProfileLogicalAuthorityAndTightening() throws Exception {
        Map<String,Object> properties=Map.of("clusterProfile","reader","subscriptionMode","topics",
                "topics","orders,audit","group","orders-logical","maxInFlight","2",
                "checkpointPolicy","require-durable");
        var definition=new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("consume",NodeKind.BEHAVIOR,KafkaConsumeNodeBehavior.BEHAVIOR,properties),
                GraphNode.error("error"), GraphNode.end("end")),List.of());
        byte[] xml;try(var graph=GraphManager.from(definition);var out=new ByteArrayOutputStream()){
            graph.writeGraphMl(out);xml=out.toByteArray();}
        String text=new String(xml,StandardCharsets.UTF_8);
        assertFalse(text.contains(KafkaTestSupport.SECRET));
        assertFalse(text.contains("broker.example.test"));
        assertFalse(text.matches("(?s).*(bootstrap|password|credentialRef|sasl|security.protocol|rr-orders-v1).*"));
        try(var reread=GraphManager.readGraphMl(new ByteArrayInputStream(xml))){
            var node=reread.definition().node("consume");
            assertEquals(KafkaConsumeNodeBehavior.BEHAVIOR,node.behavior());assertEquals(properties,node.properties());}
    }
}
