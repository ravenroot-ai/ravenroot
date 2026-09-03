package ai.ravenroot.extensions.github;

import java.util.Optional;

interface GithubOperationStore extends AutoCloseable {
    Lease begin(String tenant, String profile, String kind, String key, String requestDigest, long deadlineEpochMs);
    Optional<Record> find(String tenant, String profile, String kind, String key);
    void save(Lease lease, String state, long generation, long attempts, long deadlineEpochMs,
              String remoteId, String detailDigest, String resultJson, boolean terminal);
    void renew(Lease lease);
    void release(Lease lease);
    void audit(Lease lease, String disposition, String reason, String evidenceDigest);
    @Override default void close() { }

    record Record(String state, long generation, long attempts, long deadlineEpochMs,
                  String remoteId, String detailDigest, String resultJson, String requestDigest, boolean owned) {
        boolean terminal() { return SetNames.TERMINAL.contains(state); }
    }

    record Lease(GithubOperationStore store, String tenant, String profile, String kind, String key,
                 String owner, Record record) { }

    final class SetNames {
        static final java.util.Set<String> TERMINAL = java.util.Set.of(
                "SUCCEEDED", "FAILED", "TIMED_OUT", "STALE", "CONFLICT", "AMBIGUOUS", "CANCELLED");
        private SetNames() { }
    }
}
