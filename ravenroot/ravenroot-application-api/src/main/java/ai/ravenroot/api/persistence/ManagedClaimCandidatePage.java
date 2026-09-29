package ai.ravenroot.api.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One bounded, read-only page of execution keys that currently have eligible managed recovery work.
 * The optional cursor names the last candidate process UUID inspected, including when none of that
 * page's keys can be authorized. It is empty only after the tenant-scoped eligible set reaches its
 * end.
 *
 * @param keys candidate execution keys in deterministic process UUID order
 * @param nextAfter last inspected process UUID, or empty when the scan reached its end
 */
public record ManagedClaimCandidatePage(List<ExecutionKey> keys, Optional<UUID> nextAfter) {
    /** Canonicalizes the page as immutable, non-null values. */
    public ManagedClaimCandidatePage {
        keys = List.copyOf(keys == null ? List.of() : keys);
        nextAfter = nextAfter == null ? Optional.empty() : nextAfter;
    }
}
