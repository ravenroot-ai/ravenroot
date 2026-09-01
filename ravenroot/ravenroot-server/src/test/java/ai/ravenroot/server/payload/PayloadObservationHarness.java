package ai.ravenroot.server.payload;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.runtime.BehaviorRegistry;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observes the payload a node actually received.
 *
 * <p>The contract asserts what reached the engine rather than what the surface echoed back, so the
 * observation point has to be inside a running graph. This registers one behaviour that records
 * {@code message.payload()} and passes it through unchanged.</p>
 *
 * <p>The recorded value is wrapped in a one-element array because {@code null} is a legitimate
 * payload under API-01 and a queue cannot carry it. Distinguishing "the node observed null" from "no
 * node ran" is exactly the assertion {@code nullScalarIsAValueRatherThanAnAbsence} makes, so the
 * harness must not lose it.</p>
 */
public final class PayloadObservationHarness {

    /** start → recorder → end. The smallest graph on which a payload can be observed at a node. */
    public static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="payload-contract" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="record"><data key="kind">BEHAVIOR</data><data key="behavior">payload-recorder</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="record"><data key="outcome">continue</data></edge>
                <edge id="e2" source="record" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    public static final String BEHAVIOR = "payload-recorder";

    private final BlockingQueue<Object[]> observed = new ArrayBlockingQueue<>(64);
    private final AtomicInteger invocations = new AtomicInteger();

    /** A registry whose {@value #BEHAVIOR} node records what it was handed. */
    public BehaviorRegistry registry() {
        var registry = BehaviorRegistry.standard();
        registry.register(BEHAVIOR, message -> {
            invocations.incrementAndGet();
            observed.offer(new Object[]{message.payload()});
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });
        return registry;
    }

    /** The next observed payload, or a failure if the graph did not reach the recorder in time. */
    public Object awaitPayload() throws InterruptedException {
        Object[] holder = observed.poll(10, TimeUnit.SECONDS);
        if (holder == null) {
            throw new AssertionError("the recording node was never reached");
        }
        return holder[0];
    }

    /** Drops anything left over, so one test's execution cannot be read as the next test's. */
    public void reset() {
        observed.clear();
        invocations.set(0);
    }

    /** Number of production-style behavior invocations since {@link #reset()}. */
    public int invocations() {
        return invocations.get();
    }
}
