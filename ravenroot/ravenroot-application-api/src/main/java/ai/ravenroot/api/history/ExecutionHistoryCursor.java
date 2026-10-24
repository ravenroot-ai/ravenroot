package ai.ravenroot.api.history;
import ai.ravenroot.api.persistence.ExecutionKey;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Opaque keyset position bound to exact tenant/process/traversal/axis/filter/page size; never an authority token. */
public final class ExecutionHistoryCursor {
    private ExecutionHistoryCursor() { }
/** Opaque scope-bound keyset continuation for the same query.
 * @param key authenticated tenant and exact original process identity
 * @param traversalId actual traversal identity within the owning process
 * @param query bounded exact selection, filters, page size and continuation
 * @param position last stable keyset coordinate emitted by the selected page
 * @return opaque scope-bound keyset continuation for the same query
 */
        public static String encode(ExecutionKey key, UUID traversalId, String query, String position) {
        return "eh1." + binding(key, traversalId, query) + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(position.getBytes(StandardCharsets.UTF_8));
    }
/** Validated ordering coordinates for the same scope and query.
 * @param key authenticated tenant and exact original process identity
 * @param traversalId actual traversal identity within the owning process
 * @param query bounded exact selection, filters, page size and continuation
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @return validated ordering coordinates for the same scope and query
 */
        public static String decode(ExecutionKey key, UUID traversalId, String query, String cursor) {
        try {
            if (cursor == null || cursor.length() > 4096) throw new IllegalArgumentException();
            var parts = cursor.split("\\.", -1);
            if (parts.length != 3 || !parts[0].equals("eh1") || !parts[1].equals(binding(key, traversalId, query)))
                throw new IllegalArgumentException();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Base64.getUrlDecoder().decode(parts[2]))).toString();
        } catch (RuntimeException | java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("history cursor does not match the authorized query");
        }
    }
    private static String binding(ExecutionKey key, UUID traversal, String query) {
        try {
            var bytes = (key.tenantId() + '\0' + key.processInstanceId() + '\0' + Objects.toString(traversal, "") + '\0' + query)
                    .getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
