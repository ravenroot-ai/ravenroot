package ai.ravenroot.api.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The opaque page cursor of {@link ExecutionStore#listProcessInstances(String, ProcessInventoryQuery)}.
 *
 * <h2>Why the codec lives in the port rather than in each adapter</h2>
 * <p>A cursor is handed back to a caller and returned to <em>a</em> store, not necessarily the same
 * instance and, across a rolling deployment, not necessarily the same adapter. Two adapters that each
 * invented an encoding would produce cursors that silently fail to decode against the other, and the
 * failure mode is a page-one restart that looks like an empty result. One shared codec makes cursor
 * interoperability structural instead of a convention two implementations have to keep agreeing on.
 * It follows the shape {@code InMemoryDeploymentRegistry} established for deployment listing, extended
 * with the created-at component this ordering needs.</p>
 *
 * <h2>Opaque, versioned, tenant-bound</h2>
 * <ul>
 *   <li><strong>Opaque</strong> — Base64url of a delimited payload. It is not a documented format and
 *   a caller must not construct one; encoding it keeps a caller from coming to depend on the sort key,
 *   which is the thing that would then be unchangeable.</li>
 *   <li><strong>Versioned</strong> — the payload leads with {@link #VERSION}. A future ordering change
 *   makes old cursors decode to a rejection rather than to a position in a sequence that no longer
 *   means what the cursor assumed, which would skip or repeat rows without any error.</li>
 *   <li><strong>Tenant-bound</strong> — a cursor carries the tenant it was minted for and is refused
 *   when presented under another. That is not defence in depth over the query's own tenant scoping: it
 *   stops a cursor from being a smuggled position into a tenant the caller cannot address, and it
 *   turns a genuine caller bug — reusing a cursor across tenants — into a rejection instead of a
 *   silently wrong page. The rejection is {@link ExecutionStoreFailure.InvalidRequest} and says only
 *   that the cursor is invalid: naming the mismatch would confirm that another tenant exists.</li>
 * </ul>
 */
public final class InventoryCursor {

    /** Payload version tag. Bump it whenever the sort key changes, never for an unrelated change. */
    public static final String VERSION = "rri1";

    /**
     * NUL, matching the deployment registry's cursor. A printable separator could occur inside an
     * opaque tenant id and split one field into two, which would decode as a malformed cursor for a
     * caller that did nothing wrong.
     */
    private static final String DELIMITER = "\0";

    private InventoryCursor() {
    }

    /**
     * Mints the cursor that resumes strictly after one row.
     * @param tenantId the tenant the page was read for.
     * @param createdAt the last returned row's creation instant.
     * @param processInstanceId the last returned row's instance identity.
     * @return the opaque cursor.
     */
    public static String encode(String tenantId, Instant createdAt, UUID processInstanceId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt cannot be null");
        }
        if (processInstanceId == null) {
            throw new IllegalArgumentException("processInstanceId cannot be null");
        }
        String raw = VERSION + DELIMITER + tenantId + DELIMITER + createdAt.getEpochSecond()
                + DELIMITER + createdAt.getNano() + DELIMITER + processInstanceId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor minted for {@code tenantId}.
     *
     * <p>Every rejection carries the same message. A cursor whose tenant does not match, one whose
     * version is unknown and one that is not Base64 at all are all "invalid cursor", because a caller
     * that could distinguish them could use the difference to learn something about a tenant it does
     * not own.</p>
     *
     * @param tenantId the tenant the page is being read for.
     * @param cursor the opaque cursor to decode.
     * @return the position to resume strictly after.
     * @throws ExecutionStoreException carrying {@link ExecutionStoreFailure.InvalidRequest} when the
     *         cursor is malformed, of an unknown version, or bound to another tenant.
     */
    public static Position decode(String tenantId, String cursor) {
        if (tenantId == null || tenantId.isBlank() || cursor == null || cursor.isBlank()) {
            throw invalid();
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        String[] parts = raw.split(DELIMITER, -1);
        if (parts.length != 5 || !VERSION.equals(parts[0]) || !tenantId.equals(parts[1])) {
            throw invalid();
        }
        try {
            return new Position(
                    Instant.ofEpochSecond(Long.parseLong(parts[2]), Integer.parseInt(parts[3])),
                    UUID.fromString(parts[4]));
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static ExecutionStoreException invalid() {
        return new ExecutionStoreException(ExecutionStoreFailure.invalid(
                "the inventory cursor is not valid for this request"));
    }

    /**
     * The position a cursor names, in the listing's own sort key.
     * @param createdAt creation instant of the last row of the previous page.
     * @param processInstanceId identity of the last row of the previous page.
     */
    public record Position(Instant createdAt, UUID processInstanceId) {
        /** Rejects a decoded position that could not address a row. */
        public Position {
            if (createdAt == null) {
                throw new IllegalArgumentException("createdAt cannot be null");
            }
            if (processInstanceId == null) {
                throw new IllegalArgumentException("processInstanceId cannot be null");
            }
        }

        /**
         * Whether a candidate row sorts strictly after this position under
         * {@code (createdAt DESC, processInstanceId DESC)}.
         * @param candidateCreatedAt the candidate row's creation instant.
         * @param candidateId the candidate row's instance identity.
         * @return whether the candidate belongs to a later page.
         */
        public boolean precedes(Instant candidateCreatedAt, UUID candidateId) {
            if (candidateCreatedAt.isBefore(createdAt)) {
                return true;
            }
            if (candidateCreatedAt.isAfter(createdAt)) {
                return false;
            }
            return candidateId.toString().compareTo(processInstanceId.toString()) < 0;
        }
    }
}
