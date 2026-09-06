package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;

import java.net.URI;
import java.util.Set;

/** Immutable tenant authority for one Matrix client identity and homeserver. */
record MatrixProfile(String tenantId, String name, URI homeserverOrigin, String userId, Set<String> roomIds,
                     Set<String> eventTypes, String credentialBindingId, String credentialReference,
                     int requestTimeoutMs, int maxRequestBytes, int maxResponseBytes, int maxTextChars,
                     int maxConcurrency, int maxPerSecond, int pollTimeoutMs, int retryBackoffMs,
                     int maxEventsPerSync, InitialSyncMode initialSyncMode, String initialSince) {
    enum InitialSyncMode { SKIP, DELIVER_BOUNDED }

    MatrixProfile {
        tenantId = token(tenantId, 160); name = token(name, 64);
        homeserverOrigin = origin(homeserverOrigin);
        userId = matrixId(userId, '@', 255);
        roomIds = bounded(roomIds, 256, value -> matrixId(value, '!', 255));
        eventTypes = bounded(eventTypes, 64, value -> {
            if (!value.matches("[a-z0-9][a-z0-9._-]{0,127}")) throw configuration();
        });
        credentialBindingId = token(credentialBindingId, 256);
        credentialReference = token(credentialReference, 256);
        if (requestTimeoutMs < 1_000 || requestTimeoutMs > 60_000
                || maxRequestBytes < 1 || maxRequestBytes > 1024 * 1024
                || maxResponseBytes < 1 || maxResponseBytes > 8 * 1024 * 1024
                || maxTextChars < 1 || maxTextChars > 65_535
                || maxConcurrency < 1 || maxConcurrency > 64
                || maxPerSecond < 1 || maxPerSecond > 50
                || pollTimeoutMs < 0 || pollTimeoutMs > 30_000
                || requestTimeoutMs <= pollTimeoutMs
                || retryBackoffMs < 100 || retryBackoffMs > 60_000
                || maxEventsPerSync < 1 || maxEventsPerSync > 1_000) throw configuration();
        initialSyncMode = java.util.Objects.requireNonNull(initialSyncMode);
        initialSince = initialSince == null ? "" : opaque(initialSince, 2_048);
    }

    boolean permitsRoom(String roomId) { return roomIds.contains(roomId); }
    OutboundCredentialBinding credential() {
        return new OutboundCredentialBinding(credentialBindingId, credentialReference);
    }
    URI endpoint(String pathAndQuery) { return homeserverOrigin.resolve(pathAndQuery); }

    static String matrixId(String value, char sigil, int maximum) {
        if (value == null || value.length() < 4 || value.length() > maximum || value.charAt(0) != sigil
                || value.indexOf(':') < 2 || value.codePoints().anyMatch(c -> c < 0x21 || c == 0x7f))
            throw configuration();
        return value;
    }

    static String opaque(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(c -> c < 0x21 || c == 0x7f)) throw configuration();
        return value;
    }

    private static URI origin(URI value) {
        if (value == null || !"https".equals(value.getScheme()) || value.getHost() == null
                || value.getUserInfo() != null || value.getPort() != -1 || value.getRawQuery() != null
                || value.getFragment() != null || !(value.getRawPath().isEmpty() || "/".equals(value.getRawPath())))
            throw configuration();
        return URI.create("https://" + value.getHost().toLowerCase(java.util.Locale.ROOT) + "/");
    }

    private static String token(String value, int maximum) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0," + (maximum - 1) + "}"))
            throw configuration();
        return value;
    }
    private static Set<String> bounded(Set<String> values, int maximum, java.util.function.Consumer<String> check) {
        values = Set.copyOf(values);
        if (values.isEmpty() || values.size() > maximum) throw configuration();
        values.forEach(check); return values;
    }
    private static MatrixException configuration() { return new MatrixException(MatrixException.Code.CONFIGURATION); }
}
