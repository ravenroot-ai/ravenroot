package ai.ravenroot.api.correlation;

import java.util.Optional;
import java.util.Set;

/** Server-side exact ID/public-reference search with stable keyset pages; no download-all filtering.
 * @param applicationCorrelationId opaque business operation identity within the explicit scope
 * @param publicReference optional bounded non-secret exact lookup reference
 * @param lifecycles explicit lifecycle filter; an empty set selects all retained lifecycles
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 */
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
    /** First bounded page.
 * @param limit requested page size, checked against the operator-owned maximum
 * @return first bounded query page with no identity, reference or lifecycle filter
 */
    public static BusinessHistoryQuery first(int limit) {
        return new BusinessHistoryQuery(Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), limit);
    }
}
