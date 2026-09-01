package ai.ravenroot.extensions.spel;

import ai.ravenroot.api.payload.PayloadLimits;

import java.time.Duration;

final class SpelBounds {
    static final int MAX_EXPRESSION_LENGTH = 2_048;
    static final int MAX_EXPRESSION_UTF8 = 4_096;
    static final int MAX_AST_NODES = 128;
    static final int MAX_AST_DEPTH = 16;
    static final int MAX_OPERATIONS = 2_048;
    static final int GLOBAL_CONCURRENCY = 32;
    static final int WORKER_THREADS = 8;
    static final int QUEUE_CAPACITY = GLOBAL_CONCURRENCY - WORKER_THREADS;
    static final int PER_NODE_CONCURRENCY = 8;
    static final Duration DEADLINE = Duration.ofSeconds(1);

    static final PayloadLimits TREE = new PayloadLimits(
            1024 * 1024, 16, 256, 4_096, 64 * 1024, 256);

    private SpelBounds() {
    }
}
