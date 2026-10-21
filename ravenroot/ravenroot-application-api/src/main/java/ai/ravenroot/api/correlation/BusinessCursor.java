package ai.ravenroot.api.correlation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/** Versioned scope/filter-bound keyset cursor. It carries no authority and never changes query scope. */
public final class BusinessCursor {
    private BusinessCursor() { }
    /** Encodes stable ordering coordinates after hashing the exact scope and filters. */
    public static String encode(BusinessScope scope, String filter, List<String> coordinates) {
        String payload = String.join("\0", coordinates);
        return "bh1." + binding(scope, filter) + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
    /** Rejects foreign/changed selection, malformed, oversized and wrong-shape cursors. */
    public static List<String> decode(BusinessScope scope, String filter, String cursor, int count) {
        try {
            if (cursor == null || cursor.length() > 32768) throw new IllegalArgumentException();
            var parts = cursor.split("\\.", -1);
            if (parts.length != 3 || !parts[0].equals("bh1") || !parts[1].equals(binding(scope, filter)))
                throw new IllegalArgumentException();
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Base64.getUrlDecoder().decode(parts[2]))).toString();
            var coordinates = List.of(decoded.split("\0", -1));
            if (coordinates.size() != count || coordinates.stream().anyMatch(String::isEmpty)) throw new IllegalArgumentException();
            return coordinates;
        } catch (Exception invalid) {
            throw new BusinessHistoryException(BusinessHistoryException.Reason.INVALID_REQUEST);
        }
    }
    private static String binding(BusinessScope scope, String filter) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest((scope.tenantId() + '\0' + scope.deploymentId() + '\0' + scope.graphVersion() + '\0' + filter)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
