package ai.ravenroot.extensions.github;

import java.util.Optional;

interface GithubOperationStore extends AutoCloseable {
    Lease begin(String tenant, String profile, String kind, String key, String requestDigest, long deadlineEpochMs,
                BeginPolicy policy);
    Optional<Record> find(String tenant, String profile, String kind, String key);
    void save(Lease lease, String state, long generation, long attempts, long deadlineEpochMs,
              String remoteId, String detailDigest, String resultJson, boolean terminal);
    void saveAndAudit(Lease lease, String state, long generation, long attempts, long deadlineEpochMs,
                      String remoteId, String detailDigest, String resultJson,
                      String disposition, String reason, String evidenceDigest);
    void saveWaitingAndAuditRelease(Lease lease, long generation, long attempts, long deadlineEpochMs,
                                    String remoteId, String detailDigest, String resultJson,
                                    String reason, String evidenceDigest);
    void renew(Lease lease);
    void release(Lease lease);
    void audit(Lease lease, String disposition, String reason, String evidenceDigest);
    DeliveryDecision bindDelivery(String tenant, String profile, String deliveryId, String bindingDigest);
    @Override default void close() { }

    record Record(String state, long generation, long attempts, long deadlineEpochMs,
                  String remoteId, String detailDigest, String resultJson, String requestDigest,
                  boolean owned, boolean expiredLease, long updatedEpochMs) {
        boolean terminal() { return SetNames.TERMINAL.contains(state); }
    }

    record Lease(GithubOperationStore store, String tenant, String profile, String kind, String key,
                 String owner, Record record, boolean takeover) { }

    record BeginPolicy(boolean reconcileAmbiguous, boolean rollTerminal, long expectedGeneration,
                       long takeoverGraceMs) {
        static BeginPolicy ordinary() { return new BeginPolicy(false, false, -1, 0); }
        static BeginPolicy forAmbiguousReconciliation() { return new BeginPolicy(true, false, -1, 0); }
        static BeginPolicy forAmbiguousReconciliation(long graceMs) {
            return new BeginPolicy(true, false, -1, graceMs);
        }
        static BeginPolicy project(long expectedGeneration) {
            return new BeginPolicy(true, true, expectedGeneration, 0);
        }
        static BeginPolicy project(long expectedGeneration, long graceMs) {
            return new BeginPolicy(true, true, expectedGeneration, graceMs);
        }
    }

    enum DeliveryDecision { FIRST_SEEN, REPLAY }

    final class SetNames {
        static final java.util.Set<String> TERMINAL = java.util.Set.of(
                "SUCCEEDED", "FAILED", "TIMED_OUT", "STALE", "CONFLICT", "AMBIGUOUS", "CANCELLED");
        private SetNames() { }
    }
}
