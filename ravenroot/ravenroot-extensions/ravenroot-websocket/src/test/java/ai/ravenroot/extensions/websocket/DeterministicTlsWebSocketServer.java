package ai.ravenroot.extensions.websocket;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Raw RFC 6455 TLS peer: no WebSocket library can accidentally mirror production behavior. */
final class DeterministicTlsWebSocketServer implements AutoCloseable {
    enum Script { CAPTURE_SEND, RECEIVE_FRAGMENTS_AND_PING, REJECT_HANDSHAKE, SCRIPTED_FRAMES }
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static KeyStore keys;

    private final SSLServerSocket listener;
    private final Script script;
    private final int connections;
    private final List<List<ServerFrame>> scriptedFrames;
    private final CountDownLatch scriptRelease;
    private final Thread worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    final List<String> requestTargets = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<Map<String, String>> handshakes = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<byte[]> clientMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch completed;
    final CountDownLatch pong = new CountDownLatch(1);
    volatile byte[] pongPayload;
    volatile Throwable failure;

    DeterministicTlsWebSocketServer(Script script, int connections) throws Exception {
        this(script, connections, List.of());
    }

    private DeterministicTlsWebSocketServer(Script script, int connections,
                                            List<List<ServerFrame>> scriptedFrames) throws Exception {
        this.script = script;
        this.connections = connections;
        this.scriptedFrames = scriptedFrames.stream().map(List::copyOf).toList();
        this.scriptRelease = new CountDownLatch(script == Script.SCRIPTED_FRAMES ? 1 : 0);
        this.completed = new CountDownLatch(connections);
        listener = (SSLServerSocket) serverContext().getServerSocketFactory().createServerSocket(0);
        worker = Thread.ofVirtual().name("ravenroot-websocket-raw-tls").start(this::serve);
    }

    int port() { return listener.getLocalPort(); }

    static DeterministicTlsWebSocketServer rejectingHandshake() throws Exception {
        return new DeterministicTlsWebSocketServer(Script.REJECT_HANDSHAKE, 1);
    }

    static DeterministicTlsWebSocketServer scripted(List<List<ServerFrame>> connections) throws Exception {
        return new DeterministicTlsWebSocketServer(Script.SCRIPTED_FRAMES, connections.size(), connections);
    }

    void releaseScripts() { scriptRelease.countDown(); }

    SSLContext trustedClientContext() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, PASSWORD);
        Certificate certificate = keyStore().getCertificate("websocket-test");
        trust.setCertificateEntry("websocket-test", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), new SecureRandom());
        return context;
    }

    void awaitConnections() throws Exception {
        if (!completed.await(4, TimeUnit.SECONDS)) throw new AssertionError("raw TLS fixture did not complete");
        if (failure != null) throw new AssertionError("raw TLS fixture failed", failure);
    }

    private void serve() {
        try {
            for (int index = 0; index < connections && !closed.get(); index++) {
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(3_000);
                    Handshake handshake = readHandshake(peer.getInputStream());
                    requestTargets.add(handshake.target());
                    handshakes.add(handshake.headers());
                    if (script == Script.REJECT_HANDSHAKE) {
                        writeRefusal(peer.getOutputStream());
                        continue;
                    }
                    writeHandshake(peer.getOutputStream(), handshake.headers());
                    if (script == Script.CAPTURE_SEND) captureSend(peer);
                    else if (script == Script.RECEIVE_FRAGMENTS_AND_PING) receiveScript(peer);
                    else {
                        if (!scriptRelease.await(4, TimeUnit.SECONDS)) {
                            throw new IOException("script release timeout");
                        }
                        scripted(peer, scriptedFrames.get(index));
                    }
                } finally {
                    completed.countDown();
                }
            }
        } catch (Throwable problem) {
            if (!closed.get()) failure = problem;
            while (completed.getCount() > 0) completed.countDown();
        }
    }

    private void captureSend(Socket peer) throws IOException {
        ClientFrame frame = readClientFrame(peer.getInputStream());
        clientMessages.add(frame.payload());
        writeFrame(peer.getOutputStream(), 0x88, new byte[]{0x03, (byte) 0xE8});
    }

    private void receiveScript(Socket peer) throws IOException {
        OutputStream output = peer.getOutputStream();
        writeFrame(output, 0x01, "hel".getBytes(StandardCharsets.UTF_8));
        writeFrame(output, 0x80, "lo".getBytes(StandardCharsets.UTF_8));
        writeFrame(output, 0x82, new byte[]{1, 2, 3});
        byte[] pingValue = new byte[125];
        for (int index = 0; index < pingValue.length; index++) pingValue[index] = (byte) index;
        writeFrame(output, 0x89, pingValue);
        ClientFrame response = readClientFrame(peer.getInputStream());
        if (response.opcode() == 0xA) {
            pongPayload = response.payload();
            pong.countDown();
        }
        writeFrame(output, 0x88, new byte[]{0x03, (byte) 0xE8});
    }

    private static void scripted(Socket peer, List<ServerFrame> frames) throws IOException {
        OutputStream output = peer.getOutputStream();
        for (ServerFrame frame : frames) writeFrame(output, frame.firstByte(), frame.payload());
        writeFrame(output, 0x88, new byte[]{0x03, (byte) 0xE8});
    }

    private static Handshake readHandshake(InputStream input) throws IOException {
        String request = readAsciiLine(input);
        String[] parts = request.split(" ", 3);
        if (parts.length != 3 || !"GET".equals(parts[0])) throw new IOException("invalid upgrade request");
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while (!(line = readAsciiLine(input)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon < 1) throw new IOException("invalid upgrade header");
            headers.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).strip());
        }
        return new Handshake(parts[1], Map.copyOf(headers));
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ArrayList<Byte> bytes = new ArrayList<>();
        int previous = -1;
        for (int next; (next = input.read()) != -1;) {
            if (previous == '\r' && next == '\n') {
                bytes.removeLast();
                byte[] value = new byte[bytes.size()];
                for (int index = 0; index < bytes.size(); index++) value[index] = bytes.get(index);
                return new String(value, StandardCharsets.US_ASCII);
            }
            bytes.add((byte) next);
            if (bytes.size() > 16 * 1024) throw new IOException("upgrade too large");
            previous = next;
        }
        throw new IOException("truncated upgrade");
    }

    private static void writeHandshake(OutputStream output, Map<String, String> headers) throws Exception {
        String key = headers.get("sec-websocket-key");
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                        .getBytes(StandardCharsets.US_ASCII)));
        String protocol = headers.get("sec-websocket-protocol");
        String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n"
                + (protocol == null ? "" : "Sec-WebSocket-Protocol: " + protocol.split(",", 2)[0].strip()
                + "\r\n") + "\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeRefusal(OutputStream output) throws IOException {
        output.write(("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n"
                + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeFrame(OutputStream output, int firstByte, byte[] payload) throws IOException {
        output.write(firstByte);
        if (payload.length <= 125) {
            output.write(payload.length);
        } else {
            output.write(126);
            output.write((payload.length >>> 8) & 0xff);
            output.write(payload.length & 0xff);
        }
        output.write(payload);
        output.flush();
    }

    private static ClientFrame readClientFrame(InputStream input) throws IOException {
        int first = input.read();
        int second = input.read();
        if (first < 0 || second < 0) throw new IOException("client closed before frame");
        long length = second & 0x7f;
        if (length == 126) length = (input.read() << 8) | input.read();
        if (length == 127 || length > 1024 * 1024) throw new IOException("unsupported fixture frame");
        byte[] mask = (second & 0x80) == 0 ? new byte[0] : input.readNBytes(4);
        byte[] value = input.readNBytes((int) length);
        if (value.length != length || (mask.length != 0 && mask.length != 4)) throw new IOException("truncated frame");
        for (int index = 0; index < value.length && mask.length != 0; index++) value[index] ^= mask[index % 4];
        return new ClientFrame(first & 0x0f, value);
    }

    private static synchronized KeyStore keyStore() throws Exception {
        if (keys != null) return keys;
        Path path = Files.createTempFile("ravenroot-websocket-key-", ".p12");
        Files.deleteIfExists(path);
        try {
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "websocket-test", "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", new String(PASSWORD),
                    "-keypass", new String(PASSWORD), "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-noprompt")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("could not create test certificate");
            }
            keys = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(path)) { keys.load(input, PASSWORD); }
            return keys;
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static SSLContext serverContext() throws Exception {
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore(), PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    @Override public void close() throws Exception {
        closed.set(true);
        listener.close();
        worker.join(Duration.ofSeconds(2));
    }

    private record Handshake(String target, Map<String, String> headers) { }
    private record ClientFrame(int opcode, byte[] payload) { }
    record ServerFrame(int firstByte, byte[] payload) {
        ServerFrame {
            payload = payload.clone();
        }

        @Override public byte[] payload() { return payload.clone(); }
    }
}
