package ai.ravenroot.api.correlation;

import java.util.Optional;
import java.util.Set;

/** Server-side exact ID/public-reference search with stable keyset pages; no download-all filtering. */
public record BusinessHistoryQuery(Optional<String> applicationCorrelationId, Optional<String> publicReference,
                                   Set<BusinessHistory.Lifecycle> lifecycles, Optional<String> cursor, int limit) {
    /** Normalizes absent filters; the store enforces its configured page size and cursor scope. */
    public BusinessHistoryQuery {
        applicationCorrelationId = applicationCorrelationId == null ? Optional.empty() : applicationCorrelationId;
        publicReference = publicReference == null ? Optional.empty() : publicReference;
        lifecycles = lifecycles == null ? Set.of() : Set.copyOf(lifecycles);
        cursor = cursor == null ? Optional.empty() : cursor;
        if (limit < 1) throw new IllegalArgumentException("history page limit must be positive");
    }
    /** First bounded page. */
    public static BusinessHistoryQuery first(int limit) {
        return new BusinessHistoryQuery(Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), limit);
    }
}
