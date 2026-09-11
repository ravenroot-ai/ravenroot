package ai.ravenroot.api.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One bounded, read-only page of execution keys that may currently yield managed recovery work.
 * The optional cursor names the last process UUID inspected, including when none of that page's
 * keys can be authorized. It is empty only after the tenant-scoped scan reaches its end.
 */
public record ManagedClaimCandidatePage(List<ExecutionKey> keys, Optional<UUID> nextAfter) {
    public ManagedClaimCandidatePage {
        keys = List.copyOf(keys == null ? List.of() : keys);
        nextAfter = nextAfter == null ? Optional.empty() : nextAfter;
    }
}
