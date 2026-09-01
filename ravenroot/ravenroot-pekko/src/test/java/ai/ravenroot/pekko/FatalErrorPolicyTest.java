package ai.ravenroot.pekko;

import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the adapter stops treating an {@code Error} as one failed message (CORE-04).
 *
 * <p>Catching {@code Error} around {@code onMessage} is what stops an {@code AssertionError} from
 * killing an actor and stranding the caller's reply. The same catch applied without a line would also
 * swallow an {@code OutOfMemoryError}: the node would resume, the caller would be told one message
 * failed, and the process would carry on with a heap it has already lost — with
 * {@code pekko.jvm-exit-on-fatal-error} enabled and doing nothing, because the error never reached the
 * actor cell that policy watches.</p>
 *
 * <p>The fatal half cannot be asserted in-process: proving it works means the JVM exits. It is
 * therefore asserted in a child JVM, whose exit code and stderr are the observable outcome. The
 * recoverable half is asserted here directly, because the whole claim is that the two are different.</p>
 */
class FatalErrorPolicyTest {
    private static final SecurityContext IDENTITY =
            new SecurityContext("fatal-request", "fatal-tenant", "fatal-subject",
                    PrincipalType.WORKLOAD, "urn:ravenroot:fatal");

    @Test
    void endsTheProcessWhenANodeThrowsAnOutOfMemoryError() throws Exception {
        var child = run("java.lang.OutOfMemoryError");

        assertNotEquals(0, child.exitCode(),
                "an OutOfMemoryError inside a node must not be reported as one failed message: "
                        + child.output());
        assertTrue(child.output().contains("pekko.jvm-exit-on-fatal-error"),
                "the runtime's own fatal-error policy must be the thing that decides: " + child.output());
        assertTrue(child.output().contains("SENT"), "the child must have reached the send: " + child.output());
        assertTrue(!child.output().contains("SURVIVED"),
                "the child must not have carried on after the fatal error: " + child.output());
    }

    @Test
    void resumesTheNodeWhenItThrowsARecoverableError() throws Exception {
        // The same child, the same code path, a different Error. Asserting only the fatal case would
        // leave "catch nothing at all" passing, which is the regression this whole mechanism replaced.
        var child = run("java.lang.AssertionError");

        assertEquals(0, child.exitCode(), child.output());
        assertTrue(child.output().contains("FAILED java.lang.AssertionError"), child.output());
        assertTrue(child.output().contains("STATE RUNNING"), child.output());
        assertTrue(child.output().contains("SURVIVED"), child.output());
    }

    private static Child run(String errorType) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ThrowingNode.class.getName());
        command.add(errorType);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var buffer = new ByteArrayOutputStream();
        process.getInputStream().transferTo(buffer);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the child JVM never terminated");
        return new Child(process.exitValue(), buffer.toString(StandardCharsets.UTF_8));
    }

    private record Child(int exitCode, String output) {
    }

    /** Runs in a child JVM: one node, one message, one {@code Error} of the requested type. */
    public static final class ThrowingNode {
        private ThrowingNode() {
        }

        public static void main(String[] args) throws Exception {
            Error error = "java.lang.OutOfMemoryError".equals(args[0])
                    ? new OutOfMemoryError("simulated by the conformance suite")
                    : new AssertionError("simulated by the conformance suite");
            var engine = new PekkoExecutionEngine("fatal-error-policy-" + UUID.randomUUID());
            var ref = engine.spawn("throwing", new RavenNode() {
                @Override
                public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                    throw error;
                }
            });
            System.out.println("SENT");
            try {
                engine.send(ref, new NodeMessage(IDENTITY, UUID.randomUUID(), UUID.randomUUID(),
                        "fatal-node", "payload", Map.of())).toCompletableFuture().get(30, TimeUnit.SECONDS);
                System.out.println("COMPLETED");
            } catch (Exception failure) {
                Throwable cause = failure;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                System.out.println("FAILED " + cause.getClass().getName());
            }
            NodeLifecycleState state = engine.status(ref).orElseThrow().state();
            System.out.println("STATE " + state);
            engine.close();
            System.out.println("SURVIVED");
            System.exit(0);
        }
    }
}
