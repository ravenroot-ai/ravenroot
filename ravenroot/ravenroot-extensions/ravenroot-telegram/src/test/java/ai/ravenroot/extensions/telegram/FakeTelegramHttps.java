package ai.ravenroot.extensions.telegram;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpExchange;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

final class FakeTelegramHttps implements AutoCloseable {
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static KeyStore keys;
    private final HttpsServer server;
    private final LinkedBlockingQueue<Response> responses = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();

    FakeTelegramHttps() throws Exception {
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext()));
        server.createContext("/", this::handle);
        server.start();
    }

    URI origin() { return URI.create("https://localhost:" + server.getAddress().getPort()); }
    HttpClient client() throws Exception {
        return HttpClient.newBuilder().sslContext(clientContext()).connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    void enqueue(int status, String body) { responses.add(new Response(status, "application/json", body)); }
    void enqueue(int status, String contentType, String body) { responses.add(new Response(status, contentType, body)); }
    void disconnect() { responses.add(new Response(-1, "application/json", "")); }
    List<Request> requests() { return List.copyOf(requests); }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        requests.add(new Request(exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(body, StandardCharsets.UTF_8)));
        Response response = responses.poll();
        if (response == null) response = new Response(500, "application/json", "{\"ok\":false,\"error_code\":500}");
        if (response.status < 0) { exchange.close(); return; }
        byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", response.contentType);
        exchange.sendResponseHeaders(response.status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static SSLContext serverContext() throws Exception {
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore(), PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, new SecureRandom());
        return context;
    }
    private static SSLContext clientContext() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, PASSWORD);
        Certificate certificate = keyStore().getCertificate("telegram-test");
        trust.setCertificateEntry("telegram-test", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), new SecureRandom());
        return context;
    }
    private static synchronized KeyStore keyStore() throws Exception {
        if (keys != null) return keys;
        Path path = Files.createTempFile("ravenroot-telegram-test-", ".p12");
        try {
            Files.deleteIfExists(path);
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "telegram-test", "-keyalg", "RSA",
                    "-keysize", "2048", "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", "changeit",
                    "-keypass", "changeit", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-validity", "2", "-noprompt").redirectErrorStream(true).start();
            if (process.waitFor() != 0) throw new IOException("Could not create Telegram test certificate: "
                    + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(path)) { keys.load(input, PASSWORD); }
            return keys;
        } finally { Files.deleteIfExists(path); }
    }

    @Override public void close() { server.stop(0); }
    record Request(String path, String contentType, String body) { }
    private record Response(int status, String contentType, String body) { }
}
