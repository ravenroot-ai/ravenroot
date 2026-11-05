package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringRepository;

import org.junit.jupiter.api.Test;

/** Deterministic local provider fixture; this is not a live-provider latency measurement. */
class AuthoringReadBaselineTest {
    @Test
    void reportFixtureBaseline() {
        var actor = new GraphAuthoringRepository.Actor("tenant-a", "benchmark");
        for (int size : new int[] {10, 100, 1000}) {
            var github = new GithubAuthoringRepositoryTest.ScriptedGithub();
            github.seed(size);
            try (var repository =
                    GithubAuthoringRepositoryTest.repository(
                            github, PublicationEvidence.absent())) {
                measure(
                        repository,
                        actor,
                        "github",
                        size,
                        () -> github.benchmarkRequests.get(),
                        () -> github.benchmarkBlobs.get(),
                        () -> github.benchmarkResponseBytes.get(),
                        () -> github.benchmarkDocumentBytes.get());
            }
            var gitlab = new GitlabAuthoringRepositoryTest.Gitlab();
            gitlab.seed(size);
            try (var repository =
                    GitlabAuthoringRepositoryTest.repository(
                            gitlab, PublicationEvidence.absent())) {
                measure(
                        repository,
                        actor,
                        "gitlab",
                        size,
                        () -> gitlab.benchmarkRequests.get(),
                        () -> gitlab.benchmarkBlobs.get(),
                        () -> gitlab.benchmarkResponseBytes.get(),
                        () -> gitlab.benchmarkDocumentBytes.get());
            }
        }
    }

    private static void measure(
            GraphAuthoringRepository repository,
            GraphAuthoringRepository.Actor actor,
            String provider,
            int size,
            java.util.function.IntSupplier calls,
            java.util.function.IntSupplier blobs,
            java.util.function.LongSupplier responseBytes,
            java.util.function.LongSupplier documentBytes) {
        for (String operation : new String[] {"list", "open", "history"}) {
            long[] times = new long[10];
            int start = calls.getAsInt(), bodyStart = blobs.getAsInt();
            long responseStart = responseBytes.getAsLong(),
                    documentStart = documentBytes.getAsLong();
            for (int i = 0; i < times.length; i++) {
                long before = System.nanoTime();
                switch (operation) {
                    case "list" -> repository.list(actor, "").toCompletableFuture().join();
                    case "open" ->
                            repository
                                    .open(actor, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join();
                    default ->
                            repository
                                    .history(actor, "g0000.graphml", "")
                                    .toCompletableFuture()
                                    .join();
                }
                times[i] = System.nanoTime() - before;
            }
            java.util.Arrays.sort(times);
            System.out.printf(
                    "FIXTURE provider=%s graphs=%d operation=%s samples=10 p50_ms=%.3f p95_ms=%.3f"
                            + " calls=%d blobs=%d response_body_bytes=%d document_bytes=%d%n",
                    provider,
                    size,
                    operation,
                    times[4] / 1e6,
                    times[9] / 1e6,
                    calls.getAsInt() - start,
                    blobs.getAsInt() - bodyStart,
                    responseBytes.getAsLong() - responseStart,
                    documentBytes.getAsLong() - documentStart);
        }
    }
}
