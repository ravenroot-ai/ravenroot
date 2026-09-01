package ai.ravenroot.core.security.nodepackage;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsSigV4WireTest {
    private static final Instant TIME = Instant.parse("2015-08-30T12:36:00Z");
    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET = "wire-secret";
    private static final String REGION = "eu-west-1";
    private static final String SERVICE = "s3";

    @Test
    void percentEncodedNfdAndNfcAreVerifiedAgainstTheActualJdk21WireTarget() throws Exception {
        List<String> targets = List.of(
                "/caf%65%cc%81/%2fkeep?z=&q=e%cc%81&q=&=blank&&q=e%cc%81",
                "/caf%c3%a9/%2Fkeep?z=&q=%c3%a9&q=&=blank&&q=%c3%a9");

        for (String target : targets) {
            WireRequest captured = capture(target, "payload".getBytes(StandardCharsets.UTF_8));

            assertEquals("POST", captured.method());
            assertEquals(target, captured.target(),
                    "the JDK must emit the exact ASCII request target used to construct HttpRequest");
            assertTrue(verify(captured), "Authorization must verify from the independently captured wire values");

            assertFalse(verify(captured.withTarget(captured.target().replaceFirst("/caf", "/cab"))),
                    "an actual-wire path mutation must invalidate Authorization");
            assertFalse(verify(captured.withTarget(captured.target().replace("z=", "z=x"))),
                    "an actual-wire query mutation must invalidate Authorization");
            assertFalse(verify(captured.withHeader("x-safe", List.of("changed"))),
                    "an actual-wire signed-header mutation must invalidate Authorization");
            assertFalse(verify(captured.withBody("changed".getBytes(StandardCharsets.UTF_8))),
                    "an actual-wire body mutation must invalidate Authorization");
        }
    }

    private static WireRequest capture(String rawTarget, byte[] body) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            CompletableFuture<WireRequest> captured = new CompletableFuture<>();
            Thread reader = Thread.startVirtualThread(() -> {
                try (var connection = server.accept()) {
                    connection.setSoTimeout(5_000);
                    BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                    String[] requestLine = readLine(input).split(" ", 3);
                    Map<String, List<String>> headers = new LinkedHashMap<>();
                    String line;
                    while (!(line = readLine(input)).isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon <= 0) throw new IOException("malformed test request header");
                        headers.computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT),
                                ignored -> new ArrayList<>()).add(line.substring(colon + 1).strip());
                    }
                    int length = Integer.parseInt(headers.getOrDefault("content-length", List.of("0")).getFirst());
                    byte[] receivedBody = input.readNBytes(length);
                    if (receivedBody.length != length) throw new IOException("truncated test request body");
                    captured.complete(new WireRequest(requestLine[0], requestLine[1], immutable(headers),
                            receivedBody));
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    connection.getOutputStream().flush();
                } catch (Throwable failure) {
                    captured.completeExceptionally(failure);
                }
            });

            URI destination = URI.create("http://" + server.getInetAddress().getHostAddress() + ':'
                    + server.getLocalPort() + rawTarget);
            AwsSigV4Signer.Signed signed = AwsSigV4Signer.sign("POST", destination,
                    Map.of("x-safe", List.of("wire  value")), body, TIME,
                    (ACCESS_KEY + '\n' + SECRET).toCharArray(), REGION, SERVICE);
            HttpRequest.Builder request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(5));
            signed.headers().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
            request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .proxy(new DirectProxySelector()).build()
                    .send(request.build(), HttpResponse.BodyHandlers.discarding());

            WireRequest result = captured.get(5, TimeUnit.SECONDS);
            reader.join(Duration.ofSeconds(5));
            return result;
        }
    }

    private static boolean verify(WireRequest request) {
        try {
            String authorization = request.headers().get("authorization").getFirst();
            String credential = field(authorization, "Credential=", ", SignedHeaders=");
            String signedHeaderNames = field(authorization, "SignedHeaders=", ", Signature=");
            String suppliedSignature = authorization.substring(authorization.indexOf("Signature=") + 10);
            String scope = credential.substring(credential.indexOf('/') + 1);
            String[] scopeParts = scope.split("/", -1);
            if (scopeParts.length != 4 || !"aws4_request".equals(scopeParts[3])) return false;

            int queryStart = request.target().indexOf('?');
            String rawPath = queryStart < 0 ? request.target() : request.target().substring(0, queryStart);
            String rawQuery = queryStart < 0 ? "" : request.target().substring(queryStart + 1);
            TreeMap<String, String> canonicalHeaders = new TreeMap<>();
            for (String name : signedHeaderNames.split(";")) {
                List<String> values = request.headers().get(name);
                if (values == null) return false;
                canonicalHeaders.put(name, values.stream().map(AwsSigV4WireTest::normalizeHeader)
                        .reduce((left, right) -> left + ',' + right).orElse(""));
            }
            StringBuilder headerBlock = new StringBuilder();
            canonicalHeaders.forEach((name, value) -> headerBlock.append(name).append(':')
                    .append(value).append('\n'));
            String canonicalRequest = request.method() + '\n' + canonicalPath(rawPath) + '\n'
                    + canonicalQuery(rawQuery) + '\n' + headerBlock + '\n' + signedHeaderNames + '\n'
                    + hex(sha256(request.body()));
            String timestamp = request.headers().get("x-amz-date").getFirst();
            String stringToSign = "AWS4-HMAC-SHA256\n" + timestamp + '\n' + scope + '\n'
                    + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
            byte[] dateKey = hmac(("AWS4" + SECRET).getBytes(StandardCharsets.UTF_8), scopeParts[0]);
            byte[] regionKey = hmac(dateKey, scopeParts[1]);
            byte[] serviceKey = hmac(regionKey, scopeParts[2]);
            byte[] signingKey = hmac(serviceKey, scopeParts[3]);
            String expected = hex(hmac(signingKey, stringToSign));
            Arrays.fill(dateKey, (byte) 0);
            Arrays.fill(regionKey, (byte) 0);
            Arrays.fill(serviceKey, (byte) 0);
            Arrays.fill(signingKey, (byte) 0);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    suppliedSignature.getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String canonicalPath(String rawPath) {
        return Arrays.stream(rawPath.split("/", -1)).map(AwsSigV4WireTest::awsEncode)
                .reduce((left, right) -> left + '/' + right).orElse("");
    }

    private static String canonicalQuery(String rawQuery) {
        if (rawQuery.isEmpty()) return "";
        List<QueryPart> parts = new ArrayList<>();
        for (String pair : rawQuery.split("&", -1)) {
            int equals = pair.indexOf('=');
            parts.add(new QueryPart(awsEncode(equals < 0 ? pair : pair.substring(0, equals)),
                    awsEncode(equals < 0 ? "" : pair.substring(equals + 1))));
        }
        parts.sort(Comparator.comparing(QueryPart::name).thenComparing(QueryPart::value));
        return parts.stream().map(part -> part.name() + '=' + part.value())
                .reduce((left, right) -> left + '&' + right).orElse("");
    }

    private static String awsEncode(String raw) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(raw.length());
        for (int index = 0; index < raw.length();) {
            char current = raw.charAt(index);
            if (current == '%') {
                int high = Character.digit(raw.charAt(index + 1), 16);
                int low = Character.digit(raw.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("bad escape");
                decoded.write((high << 4) | low);
                index += 3;
            } else {
                decoded.writeBytes(String.valueOf(current).getBytes(StandardCharsets.UTF_8));
                index++;
            }
        }
        StringBuilder encoded = new StringBuilder();
        for (byte rawByte : decoded.toByteArray()) {
            int value = rawByte & 0xff;
            if (value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                    || value >= '0' && value <= '9' || value == '-' || value == '_'
                    || value == '.' || value == '~') {
                encoded.append((char) value);
            } else {
                encoded.append('%').append(String.format(Locale.ROOT, "%02X", value));
            }
        }
        return encoded.toString();
    }

    private static String normalizeHeader(String value) {
        return value.strip().replaceAll("[ \\t]+", " ");
    }

    private static String field(String authorization, String prefix, String suffix) {
        int start = authorization.indexOf(prefix);
        int end = authorization.indexOf(suffix);
        if (start < 0 || end < start) throw new IllegalArgumentException("bad authorization");
        return authorization.substring(start + prefix.length(), end);
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() <= 16 * 1024) {
            int current = input.read();
            if (current < 0) throw new IOException("unexpected end of test request");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            previous = current;
        }
        throw new IOException("oversized test request line");
    }

    private static Map<String, List<String>> immutable(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private record QueryPart(String name, String value) { }

    private record WireRequest(String method, String target, Map<String, List<String>> headers, byte[] body) {
        private WireRequest {
            body = body.clone();
        }

        private WireRequest withTarget(String changed) {
            return new WireRequest(method, changed, headers, body);
        }

        private WireRequest withHeader(String name, List<String> values) {
            Map<String, List<String>> changed = new LinkedHashMap<>(headers);
            changed.put(name, List.copyOf(values));
            return new WireRequest(method, target, Map.copyOf(changed), body);
        }

        private WireRequest withBody(byte[] changed) {
            return new WireRequest(method, target, headers, changed);
        }

        @Override public byte[] body() { return body.clone(); }
    }

    private static final class DirectProxySelector extends ProxySelector {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
    }
}
