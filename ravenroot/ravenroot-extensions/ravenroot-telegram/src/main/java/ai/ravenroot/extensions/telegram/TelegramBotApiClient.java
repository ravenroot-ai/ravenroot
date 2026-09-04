package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Fixed-origin, bounded and secret-safe Telegram Bot API transport shared by every behavior. */
final class TelegramBotApiClient {
    static final URI PRODUCTION_ORIGIN = URI.create("https://api.telegram.org");
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final PayloadLimits JSON_LIMITS =
            new PayloadLimits(MAX_RESPONSE_BYTES, 16, 256, 2_048, 32_768, 256);

    private final CredentialResolver credentials;
    private final ClientFactory clients;
    private final URI origin;
    private final ReservedNetworkPolicy destinationPolicy;

    TelegramBotApiClient(CredentialResolver credentials) {
        this(credentials, timeout -> HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build(), PRODUCTION_ORIGIN);
    }

    TelegramBotApiClient(CredentialResolver credentials, HttpClient client, URI origin) {
        this(credentials, ignored -> client, origin);
    }

    TelegramBotApiClient(CredentialResolver credentials, ClientFactory clients, URI origin) {
        this(credentials, clients, origin, ReservedNetworkPolicy.fromEnvironment(System.getenv()));
    }

    TelegramBotApiClient(CredentialResolver credentials, ClientFactory clients, URI origin,
                         ReservedNetworkPolicy destinationPolicy) {
        URI normalized = normalizeOrigin(origin);
        destinationPolicy.requireAllowedLiteral(normalized.getHost());
        this.credentials = java.util.Objects.requireNonNull(credentials);
        this.clients = java.util.Objects.requireNonNull(clients);
        this.origin = normalized;
        this.destinationPolicy = java.util.Objects.requireNonNull(destinationPolicy);
    }

    ReservedNetworkPolicy destinationPolicy() { return destinationPolicy; }

    Outcome call(TelegramProfile profile, int requestTimeoutMs, int retries, String method, Body body) {
        String token = resolveToken(profile.credentialRef());
        final HttpClient client;
        try { client = clients.create(Duration.ofMillis(profile.connectTimeoutMs())); }
        catch (RuntimeException unavailable) {
            return new Outcome(State.TRANSPORT_UNAVAILABLE, null, 0);
        }
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint(token, method))
                        .timeout(Duration.ofMillis(requestTimeoutMs))
                        .header("Content-Type", body.contentType)
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes)).build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                ApiResponse api = ApiResponse.read(response);
                if (api.code == 429 && api.retryAfter != null && attempt <= retries && api.retryAfter <= 5) {
                    waitSeconds(api.retryAfter);
                    continue;
                }
                return new Outcome(State.RESPONSE, api, attempt);
            } catch (HttpConnectTimeoutException | ConnectException notConnected) {
                if (attempt <= retries) {
                    if (backoff(attempt)) continue;
                    return new Outcome(State.AMBIGUOUS, null, attempt);
                }
                return new Outcome(State.CONNECT_FAILED, null, attempt);
            } catch (HttpTimeoutException timeout) {
                return new Outcome(State.AMBIGUOUS, null, attempt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Outcome(State.AMBIGUOUS, null, attempt);
            } catch (IOException | RuntimeException failure) {
                return new Outcome(State.AMBIGUOUS, null, attempt);
            }
        }
        return new Outcome(State.CONNECT_FAILED, null, retries + 1);
    }

    private String resolveToken(String reference) {
        final SecretValue secret;
        try {
            Optional<SecretValue> resolved = credentials.resolve(reference);
            secret = resolved == null ? null : resolved.orElse(null);
        } catch (RuntimeException resolverFailure) {
            throw credentialUnavailable();
        }
        if (secret == null) throw credentialUnavailable();
        final char[] copy;
        try { copy = secret.copy(); }
        catch (RuntimeException resolverFailure) {
            secret.close();
            throw credentialUnavailable();
        }
        try {
            String token = new String(copy);
            if (!token.matches("[0-9]{1,20}:[A-Za-z0-9_-]{20,200}")) throw credentialUnavailable();
            return token;
        } finally {
            Arrays.fill(copy, '\0');
            secret.close();
        }
    }

    private URI endpoint(String token, String method) {
        try { return URI.create(origin + "/bot" + token + "/" + method); }
        catch (RuntimeException invalid) { throw credentialUnavailable(); }
    }

    private static URI normalizeOrigin(URI value) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
                || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null
                || (value.getPath() != null && !value.getPath().isEmpty() && !"/".equals(value.getPath())))
            throw new IllegalArgumentException("Telegram origin must be an HTTPS origin");
        String text = value.toString();
        return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
    }

    private static TelegramSendException credentialUnavailable() {
        return new TelegramSendException(TelegramSendException.Code.CREDENTIAL_UNAVAILABLE,
                "Telegram credential is unavailable");
    }

    private static void waitSeconds(int seconds) throws InterruptedException { Thread.sleep(seconds * 1_000L); }
    private static boolean backoff(int attempt) {
        try {
            Thread.sleep(Math.min(500L, 50L << Math.min(attempt - 1, 3)));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    record Body(byte[] bytes, String contentType) {
        Body { bytes = Arrays.copyOf(bytes, bytes.length); }
        @Override public byte[] bytes() { return Arrays.copyOf(bytes, bytes.length); }
    }

    enum State { RESPONSE, CONNECT_FAILED, AMBIGUOUS, TRANSPORT_UNAVAILABLE }
    enum RemoteReason { NONE, CALLBACK_EXPIRED, NOT_MODIFIED, NOT_FOUND, PERMISSION_DENIED, OTHER }

    record Outcome(State state, ApiResponse response, int attempt) { }

    record ApiResponse(int httpStatus, boolean ok, int code, Map<?, ?> result, Boolean booleanResult,
                       Integer retryAfter, Long migrateTo, RemoteReason reason) {
        static ApiResponse read(HttpResponse<InputStream> response) throws IOException {
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) throw new IOException("unexpected response media type");
            byte[] body;
            try (InputStream input = response.body()) { body = input.readNBytes(MAX_RESPONSE_BYTES + 1); }
            if (body.length > MAX_RESPONSE_BYTES) throw new IOException("response too large");
            Object java = PayloadJson.read(body, JSON_LIMITS).toJava();
            if (!(java instanceof Map<?, ?> map) || !(map.get("ok") instanceof Boolean ok))
                throw new IOException("malformed response");
            int code = number(map.get("error_code"), response.statusCode());
            Map<?, ?> result = map.get("result") instanceof Map<?, ?> resultMap ? resultMap : null;
            Boolean booleanResult = map.get("result") instanceof Boolean flag ? flag : null;
            Integer retry = null;
            Long migrate = null;
            if (map.get("parameters") instanceof Map<?, ?> parameters) {
                Long retryValue = longNumber(parameters.get("retry_after"));
                if (retryValue != null && retryValue >= 0 && retryValue <= 86_400) retry = retryValue.intValue();
                migrate = longNumber(parameters.get("migrate_to_chat_id"));
            }
            String description = map.get("description") instanceof String text
                    ? text.toLowerCase(Locale.ROOT) : "";
            return new ApiResponse(response.statusCode(), ok, code, result, booleanResult, retry, migrate,
                    classify(description));
        }

        private static RemoteReason classify(String description) {
            if (description.contains("query is too old") || description.contains("query id is invalid"))
                return RemoteReason.CALLBACK_EXPIRED;
            if (description.contains("message is not modified")) return RemoteReason.NOT_MODIFIED;
            if (description.contains("message to edit not found") || description.contains("message to delete not found")
                    || description.contains("message not found")) return RemoteReason.NOT_FOUND;
            if (description.contains("not enough rights") || description.contains("have no rights")
                    || description.contains("message can't be edited") || description.contains("message can't be deleted"))
                return RemoteReason.PERMISSION_DENIED;
            return description.isEmpty() ? RemoteReason.NONE : RemoteReason.OTHER;
        }

        private static int number(Object value, int fallback) {
            Long result = longNumber(value);
            return result == null || result < Integer.MIN_VALUE || result > Integer.MAX_VALUE
                    ? fallback : result.intValue();
        }

        private static Long longNumber(Object value) {
            return value instanceof Number number && !(number instanceof Double || number instanceof Float)
                    ? number.longValue() : null;
        }
    }

    @FunctionalInterface interface ClientFactory { HttpClient create(Duration connectTimeout); }
}
