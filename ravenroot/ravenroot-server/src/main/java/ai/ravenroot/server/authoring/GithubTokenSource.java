package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.CredentialResolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/** Server-only PAT or GitHub App installation-token resolver. */
final class GithubTokenSource {
    private static final PayloadLimits JSON_LIMITS = new PayloadLimits(256 * 1024, 12, 1000, 10_000,
            128 * 1024, 256);

    private final GraphAuthoringConfiguration configuration;
    private final CredentialResolver credentials;
    private final HttpClient client;
    private final Clock clock;
    private volatile CachedToken cached;

    GithubTokenSource(GraphAuthoringConfiguration configuration, CredentialResolver credentials,
                      HttpClient client, Clock clock) {
        this.configuration = configuration;
        this.credentials = credentials;
        this.client = client;
        this.clock = clock;
    }

    String token() {
        if (configuration.credentialMode() == GraphAuthoringConfiguration.CredentialMode.PAT) {
            return resolveSecret();
        }
        CachedToken current = cached;
        if (current != null && current.expiresAt().isAfter(clock.instant().plusSeconds(60))) return current.value();
        synchronized (this) {
            current = cached;
            if (current != null && current.expiresAt().isAfter(clock.instant().plusSeconds(60))) return current.value();
            cached = current = mintInstallationToken();
            return current.value();
        }
    }

    void invalidate(String value) {
        CachedToken current = cached;
        if (current != null && current.value().equals(value)) cached = null;
    }

    private String resolveSecret() {
        var resolved = credentials.resolve(configuration.credentialReference())
                .orElseThrow(() -> new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE));
        try (resolved) {
            char[] copy = resolved.copy();
            try {
                String value = new String(copy);
                if (value.isBlank()) throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
                return value;
            } finally {
                Arrays.fill(copy, '\0');
            }
        }
    }

    private CachedToken mintInstallationToken() {
        String jwt = appJwt();
        URI uri = installationTokenUri();
        try {
            byte[] requestBody = PayloadJson.writeJava(Map.of(
                    "repositories", java.util.List.of(configuration.repositoryName()),
                    "permissions", Map.of("contents", "write", "pull_requests", "write", "metadata", "read")),
                    JSON_LIMITS);
            var request = HttpRequest.newBuilder(uri).timeout(configuration.requestTimeout())
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + jwt)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            var response = client.send(request,
                    BoundedHttpResponseBody.upTo(JSON_LIMITS.maxEncodedBytes(), configuration.requestTimeout()));
            byte[] responseBody = response.body();
            if (response.statusCode() != 201 || responseBody.length > JSON_LIMITS.maxEncodedBytes()) {
                throw new GraphAuthoringException(response.statusCode() == 401 || response.statusCode() == 403
                        ? GraphAuthoringException.Failure.UNAUTHORIZED : GraphAuthoringException.Failure.UNAVAILABLE);
            }
            Map<String, Object> body = object(responseBody);
            String token = string(body, "token");
            Instant expires;
            try { expires = Instant.parse(string(body, "expires_at")); }
            catch (DateTimeParseException invalid) { throw unavailable(invalid); }
            Instant now = clock.instant();
            if (!expires.isAfter(now.plusSeconds(60)) || expires.isAfter(now.plus(Duration.ofHours(2)))) {
                throw unavailable(new IllegalArgumentException("invalid installation-token expiration"));
            }
            return new CachedToken(token, expires);
        } catch (GraphAuthoringException classified) {
            throw classified;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable(interrupted);
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    private String appJwt() {
        String pem = resolveSecret();
        try {
            byte[] keyBytes;
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                byte[] pkcs1 = decodePem(pem, "RSA PRIVATE KEY");
                keyBytes = wrapPkcs1(pkcs1);
            } else {
                keyBytes = decodePem(pem, "PRIVATE KEY");
            }
            var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            long now = clock.instant().getEpochSecond();
            String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
            String payload = base64Url("{\"iat\":" + (now - 30) + ",\"exp\":" + (now + 540)
                    + ",\"iss\":\"" + json(configuration.appId()) + "\"}");
            String signed = header + "." + payload;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GraphAuthoringException classified) {
            throw classified;
        } catch (Exception failure) {
            throw unavailable(failure);
        }
    }

    private URI api(String endpoint) {
        String base = configuration.apiBase().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + endpoint);
    }

    URI installationTokenUri() {
        return api("/app/installations/" + configuration.installationId() + "/access_tokens");
    }

    private static byte[] decodePem(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int start = pem.indexOf(begin);
        int finish = pem.indexOf(end);
        if (start < 0 || finish <= start) throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
        String encoded = pem.substring(start + begin.length(), finish).replaceAll("\\s", "");
        try { return Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw unavailable(invalid); }
    }

    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algorithm = new byte[] {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] version = new byte[] {0x02, 0x01, 0x00};
        byte[] octet = der((byte) 0x04, pkcs1);
        byte[] body = concat(version, algorithm, octet);
        return der((byte) 0x30, body);
    }

    private static byte[] der(byte tag, byte[] body) {
        byte[] length;
        if (body.length < 128) length = new byte[] {(byte) body.length};
        else if (body.length < 256) length = new byte[] {(byte) 0x81, (byte) body.length};
        else length = new byte[] {(byte) 0x82, (byte) (body.length >>> 8), (byte) body.length};
        return concat(new byte[] {tag}, length, body);
    }

    private static byte[] concat(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) { System.arraycopy(value, 0, result, offset, value.length); offset += value.length; }
        return result;
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(byte[] bytes) {
        Object value = PayloadJson.read(bytes, JSON_LIMITS).toJava();
        if (!(value instanceof Map<?, ?> map)) throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
        return (Map<String, Object>) map;
    }

    private static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
        return text;
    }

    private static String json(String value) {
        return PayloadJson.write(PayloadValue.of(value)).substring(1,
                PayloadJson.write(PayloadValue.of(value)).length() - 1);
    }

    private static GraphAuthoringException unavailable(Throwable cause) {
        return new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE, cause);
    }

    private record CachedToken(String value, Instant expiresAt) { }
}
