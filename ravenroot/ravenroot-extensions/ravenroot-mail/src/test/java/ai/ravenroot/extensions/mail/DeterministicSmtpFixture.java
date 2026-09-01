package ai.ravenroot.extensions.mail;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A deliberately small SMTP state machine: it makes transport edge cases observable without a mail-server dependency. */
final class DeterministicSmtpFixture implements AutoCloseable {
    enum Mode { PLAIN, STARTTLS, SMTPS }

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static KeyStore keys;

    private final Mode mode;
    private final boolean advertiseStartTls;
    private final String rejectedRecipient;
    private final boolean disconnectFirstConnection;
    private final boolean rejectAuthentication;
    private final String authenticationSecret;
    private final boolean dropAfterData;
    private final ServerSocket socket;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger dataAccepted = new AtomicInteger();
    private final AtomicInteger authenticationAttempts = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong firstConnectionNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong secondConnectionNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.CountDownLatch firstDisconnectReached = new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch releaseFirstDisconnect = new java.util.concurrent.CountDownLatch(1);
    private final AtomicBoolean pauseFirstDisconnect = new AtomicBoolean();
    private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();

    private DeterministicSmtpFixture(Mode mode, boolean advertiseStartTls, String rejectedRecipient,
                                     boolean disconnectFirstConnection, boolean rejectAuthentication,
                                     String authenticationSecret, boolean dropAfterData) throws Exception {
        this.mode = mode;
        this.advertiseStartTls = advertiseStartTls;
        this.rejectedRecipient = rejectedRecipient;
        this.disconnectFirstConnection = disconnectFirstConnection;
        this.rejectAuthentication = rejectAuthentication;
        this.authenticationSecret = authenticationSecret;
        this.dropAfterData = dropAfterData;
        this.socket = mode == Mode.SMTPS ? tlsContext().getServerSocketFactory().createServerSocket(0)
                : ServerSocketFactory.getDefault().createServerSocket(0);
        this.worker = new Thread(this::serve, "deterministic-smtp-fixture");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    static DeterministicSmtpFixture start(Mode mode, boolean advertiseStartTls, String rejectedRecipient,
                                          boolean disconnectFirstConnection) throws Exception {
        return new DeterministicSmtpFixture(mode, advertiseStartTls, rejectedRecipient, disconnectFirstConnection, false, null, false);
    }

    static DeterministicSmtpFixture rejectingAuthentication() throws Exception {
        return new DeterministicSmtpFixture(Mode.STARTTLS, true, null, false, true, "credential-secret-sentinel", false);
    }

    static DeterministicSmtpFixture acceptingAuthentication(String secret) throws Exception {
        return new DeterministicSmtpFixture(Mode.STARTTLS, true, null, false, false, secret, false);
    }

    static DeterministicSmtpFixture disconnectingOnceThenAcceptingAuthentication(String secret) throws Exception {
        return new DeterministicSmtpFixture(Mode.STARTTLS, true, null, true, false, secret, false);
    }

    static DeterministicSmtpFixture droppingAfterData() throws Exception {
        return new DeterministicSmtpFixture(Mode.PLAIN, false, null, false, false, null, true);
    }

    int port() { return socket.getLocalPort(); }
    int connections() { return connections.get(); }
    int dataAccepted() { return dataAccepted.get(); }
    int authenticationAttempts() { return authenticationAttempts.get(); }
    long retryGapMillis() { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(secondConnectionNanos.get() - firstConnectionNanos.get()); }
    void pauseBeforeFirstDisconnect() { pauseFirstDisconnect.set(true); }
    boolean awaitFirstDisconnectConnection() throws InterruptedException {
        return firstDisconnectReached.await(2, java.util.concurrent.TimeUnit.SECONDS);
    }
    void releaseFirstDisconnect() { releaseFirstDisconnect.countDown(); }

    private void serve() {
        while (running.get()) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException acceptFailure) {
                if (running.get()) workerFailure.compareAndSet(null, acceptFailure);
                return;
            }
            try (client) {
                int connection = connections.incrementAndGet();
                if (connection == 1) firstConnectionNanos.set(System.nanoTime());
                if (connection == 2) secondConnectionNanos.set(System.nanoTime());
                if (disconnectFirstConnection && connection == 1) {
                    firstDisconnectReached.countDown();
                    if (pauseFirstDisconnect.get()) {
                        try { releaseFirstDisconnect.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    continue;
                }
                handle(client);
            } catch (IOException ignored) { /* Client disconnects are intentional in negative transport tests. */ }
        }
    }

    private void handle(Socket client) throws IOException {
        Socket active = client;
        if (mode == Mode.SMTPS) ((SSLSocket) active).startHandshake();
        BufferedReader in = reader(active);
        OutputStream out = active.getOutputStream();
        response(out, "220 fixture ready");
        boolean acceptedRecipient = false;
        boolean tlsEstablished = mode == Mode.SMTPS;
        for (String line; (line = in.readLine()) != null;) {
            String command = line.toUpperCase(Locale.ROOT);
            if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                if (advertiseStartTls && mode == Mode.STARTTLS && !tlsEstablished) response(out, "250-fixture\r\n250-STARTTLS\r\n250 OK");
                else if (authenticationSecret != null) response(out, "250-fixture\r\n250-AUTH PLAIN\r\n250 OK");
                else response(out, "250 OK");
            } else if (command.equals("STARTTLS")) {
                if (mode != Mode.STARTTLS || !advertiseStartTls) { response(out, "502 STARTTLS unavailable"); continue; }
                response(out, "220 Begin TLS");
                try {
                    active = tlsContext().getSocketFactory().createSocket(active, "127.0.0.1", port(), false);
                    ((SSLSocket) active).setUseClientMode(false);
                    ((SSLSocket) active).startHandshake();
                    in = reader(active); out = active.getOutputStream();
                    tlsEstablished = true;
                } catch (Exception failure) { throw new IOException("Could not start TLS", failure); }
            } else if (command.startsWith("AUTH") && authenticationSecret != null) {
                authenticationAttempts.incrementAndGet();
                String[] parts = line.split(" ", 3);
                String decoded = parts.length == 3
                        ? new String(java.util.Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8).replace('\0', ':') : "";
                if (rejectAuthentication) response(out, "535 " + decoded);
                else if (decoded.contains(authenticationSecret)) response(out, "235 Authentication accepted");
                else response(out, "535 Authentication rejected");
            } else if (command.startsWith("MAIL FROM")) {
                response(out, "250 Sender accepted");
            } else if (command.startsWith("RCPT TO")) {
                if (rejectedRecipient != null && command.contains(rejectedRecipient.toUpperCase(Locale.ROOT))) response(out, "550 recipient rejected");
                else { acceptedRecipient = true; response(out, "250 Recipient accepted"); }
            } else if (command.equals("DATA")) {
                if (!acceptedRecipient) { response(out, "554 no recipient accepted"); continue; }
                response(out, "354 End data with <CR><LF>.<CR><LF>");
                while ((line = in.readLine()) != null && !line.equals(".")) { }
                dataAccepted.incrementAndGet();
                if (dropAfterData) return;
                response(out, "250 Message accepted");
            } else if (command.equals("QUIT")) { response(out, "221 Bye"); return; }
            else response(out, "250 OK");
        }
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
    }
    private static void response(OutputStream out, String value) throws IOException {
        out.write((value + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
    }

    static TlsDefaults trustFixtureCertificate() throws Exception {
        SSLContext before = SSLContext.getDefault();
        KeyStore keys = keyStore();
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, PASSWORD);
        Certificate certificate = keys.getCertificate("smtp-test"); trust.setCertificateEntry("smtp-test", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        SSLContext trusted = SSLContext.getInstance("TLS"); trusted.init(null, managers.getTrustManagers(), new SecureRandom());
        SSLContext.setDefault(trusted); return new TlsDefaults(before);
    }

    private static SSLContext tlsContext() throws Exception {
        KeyStore keys = keyStore();
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), null, new SecureRandom()); return context;
    }
    private static synchronized KeyStore keyStore() throws Exception {
        if (keys != null) return keys;
        Path path = Files.createTempFile("ravenroot-smtp-test-", ".p12");
        try {
            Files.deleteIfExists(path); // keytool creates the keystore itself and rejects a pre-existing empty file.
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "smtp-test", "-keyalg", "RSA",
                    "-keysize", "2048", "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", "changeit",
                    "-keypass", "changeit", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-validity", "2", "-noprompt").redirectErrorStream(true).start();
            if (process.waitFor() != 0) throw new IOException("Could not create SMTP test certificate: " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(path)) { keys.load(input, PASSWORD); }
            return keys;
        } finally { Files.deleteIfExists(path); }
    }

    static final class TlsDefaults implements AutoCloseable {
        private final SSLContext before;
        private TlsDefaults(SSLContext before) { this.before = before; }
        @Override public void close() { SSLContext.setDefault(before); }
    }

    @Override public void close() throws Exception {
        running.set(false); socket.close(); worker.join(2_000);
        if (worker.isAlive()) throw new IllegalStateException("SMTP fixture worker did not terminate");
        if (workerFailure.get() != null) throw new IllegalStateException("SMTP fixture accept loop failed", workerFailure.get());
    }
}
