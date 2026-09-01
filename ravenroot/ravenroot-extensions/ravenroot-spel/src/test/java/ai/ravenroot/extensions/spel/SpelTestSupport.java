package ai.ravenroot.extensions.spel;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

final class SpelTestSupport {
    private SpelTestSupport() {
    }

    static NodeMessage message(Object payload) {
        UUID process = UUID.randomUUID();
        UUID traversal = UUID.randomUUID();
        UUID invocation = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request-a", "tenant-a", "tester",
                        PrincipalType.USER, "test-issuer"),
                process, traversal, invocation, UUID.randomUUID(), Set.of(), "spel", payload,
                Map.of("trace", "preserved"));
    }

    static SpelNodeException failure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (!(current instanceof SpelNodeException classified)) {
            throw new AssertionError("expected classified SpEL failure, got " + current.getClass().getName());
        }
        return classified;
    }
}
