package ai.ravenroot.extensions.mail.imap;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** GreenMail backed IMAP fixture with a localhost-SAN certificate and session-scoped trust. */
final class DeterministicImapFixture implements AutoCloseable {
    private static final String STORE_FILE = "greenmail.tls.keystore.file";
    private static final String STORE_PASSWORD = "greenmail.tls.keystore.password";
    private static final String KEY_PASSWORD = "greenmail.tls.key.password";
    private static final String TRUST_STORE = "javax.net.ssl.trustStore";
    private static final String TRUST_STORE_PASSWORD = "javax.net.ssl.trustStorePassword";
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static KeyStore keys;

    private final GreenMail greenMail;
    private final Map<String, String> previous;
    private final int port;
    private final Path retainedStore;

    private DeterministicImapFixture(GreenMail greenMail, Map<String, String> previous, int port,
                                     Path retainedStore) {
        this.greenMail = greenMail;
        this.previous = previous;
        this.port = port;
        this.retainedStore = retainedStore;
    }

    static DeterministicImapFixture startImaps() throws Exception {
        Path store = createStore();
        Map<String, String> previous = new LinkedHashMap<>();
        try {
            set(previous, STORE_FILE, store.toString()); set(previous, STORE_PASSWORD, new String(PASSWORD)); set(previous, KEY_PASSWORD, new String(PASSWORD));
            GreenMail server = new GreenMail(new ServerSetup(0, "0.0.0.0", ServerSetup.PROTOCOL_IMAPS));
            server.start();
            return new DeterministicImapFixture(server, previous, server.getImaps().getPort(), null);
        } finally {
            restore(previous);
            Files.deleteIfExists(store);
        }
    }

    static DeterministicImapFixture startImapsWithDefaultTrust(int port) throws Exception {
        return startFixedPortImaps(port, "0.0.0.0");
    }

    /** Fixed-port fixture for the installed-bundle Docker proof; the bundle uses only public defaults. */
    static DeterministicImapFixture startInstalledBundleImaps(int port) throws Exception {
        return startFixedPortImaps(port, "127.0.0.1");
    }

    private static DeterministicImapFixture startFixedPortImaps(int port, String bindAddress) throws Exception {
        Path store = createStore();
        Map<String, String> previous = new LinkedHashMap<>();
        try {
            set(previous, STORE_FILE, store.toString());
            set(previous, STORE_PASSWORD, new String(PASSWORD));
            set(previous, KEY_PASSWORD, new String(PASSWORD));
            set(previous, TRUST_STORE, store.toString());
            set(previous, TRUST_STORE_PASSWORD, new String(PASSWORD));
            set(previous, "javax.net.ssl.trustStoreType", "PKCS12");
            GreenMail server = new GreenMail(new ServerSetup(port, bindAddress, ServerSetup.PROTOCOL_IMAPS));
            server.start();
            return new DeterministicImapFixture(server, previous, port, store);
        } catch (Throwable failure) {
            restore(previous);
            Files.deleteIfExists(store);
            throw failure;
        }
    }

    int port() { return port; }
    GreenMail server() { return greenMail; }
    SSLSocketFactory trustedSocketFactory() throws Exception { return trustedSocketFactoryForTests(); }
    static SSLSocketFactory trustedSocketFactoryForTests() throws Exception { return new DelegatingSocketFactory(trustedContext().getSocketFactory()); }

    private static synchronized Path createStore() throws Exception {
        KeyStore store = keyStore();
        Path path = Files.createTempFile("ravenroot-imap-test-", ".p12");
        try (var output = Files.newOutputStream(path)) { store.store(output, PASSWORD); }
        return path;
    }
    private static synchronized KeyStore keyStore() throws Exception {
        if (keys != null) return keys;
        Path path = Files.createTempFile("ravenroot-imap-key-", ".p12");
        try {
            Files.deleteIfExists(path);
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "imap-test", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-noprompt").redirectErrorStream(true).start();
            if (process.waitFor() != 0) throw new IOException("Could not create IMAP test certificate: " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(path)) { keys.load(input, PASSWORD); }
            return keys;
        } finally { Files.deleteIfExists(path); }
    }
    static SSLContext serverContextForTests() throws Exception {
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keyStore(), PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), null, new SecureRandom()); return context;
    }
    private static SSLContext trustedContext() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, PASSWORD);
        Certificate certificate = keyStore().getCertificate("imap-test"); trust.setCertificateEntry("imap-test", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, managers.getTrustManagers(), new SecureRandom()); return context;
    }
    private static void set(Map<String, String> previous, String key, String value) { previous.put(key, System.getProperty(key)); System.setProperty(key, value); }
    private static void restore(Map<String, String> previous) { previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }); }
    @Override public void close() {
        greenMail.stop();
        restore(previous);
        if (retainedStore != null) try { Files.deleteIfExists(retainedStore); }
        catch (IOException ignored) { }
    }

    private static final class DelegatingSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private DelegatingSocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return delegate.createSocket(); }
        @Override public Socket createSocket(String host, int port) throws IOException { return delegate.createSocket(host, port); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return delegate.createSocket(host, port, local, localPort); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return delegate.createSocket(host, port); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return delegate.createSocket(host, port, local, localPort); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws IOException { return delegate.createSocket(socket, host, port, close); }
    }
}
