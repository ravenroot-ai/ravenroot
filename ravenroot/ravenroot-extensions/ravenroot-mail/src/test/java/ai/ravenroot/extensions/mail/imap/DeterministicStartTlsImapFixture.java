package ai.ravenroot.extensions.mail.imap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/** A bounded IMAP state machine used only to make STARTTLS and teardown observable. */
public final class DeterministicStartTlsImapFixture implements AutoCloseable {
    private final boolean advertiseStartTls;
    private final boolean acceptStartTls;
    private final String authenticationFailure;
    private final ServerSocket listener = new ServerSocket(0);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean upgraded = new AtomicBoolean();
    private final AtomicInteger credentialCommands = new AtomicInteger();
    private final AtomicInteger closeCommands = new AtomicInteger();
    private final AtomicInteger logoutCommands = new AtomicInteger();
    private final AtomicInteger socketsClosed = new AtomicInteger();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Thread worker;

    public DeterministicStartTlsImapFixture(boolean advertiseStartTls, boolean acceptStartTls) throws IOException { this(advertiseStartTls, acceptStartTls, null); }
    public DeterministicStartTlsImapFixture(boolean advertiseStartTls, boolean acceptStartTls, String authenticationFailure) throws IOException {
        this.advertiseStartTls = advertiseStartTls; this.acceptStartTls = acceptStartTls; this.authenticationFailure = authenticationFailure;
        worker = new Thread(this::serve, "deterministic-starttls-imap"); worker.setDaemon(true); worker.start();
    }
    int port() { return listener.getLocalPort(); }
    boolean upgraded() { return upgraded.get(); }
    int credentialCommands() { return credentialCommands.get(); }
    int closeCommands() { return closeCommands.get(); }
    int logoutCommands() { return logoutCommands.get(); }
    int socketsClosed() { return socketsClosed.get(); }
    boolean awaitSocketClose() throws InterruptedException { return closed.await(1, TimeUnit.SECONDS); }

    private void serve() {
        while (running.get()) try {
            Socket socket = listener.accept();
            try (socket) { handle(socket); } finally { socketsClosed.incrementAndGet(); closed.countDown(); }
        } catch (IOException ignored) { if (!running.get()) return; }
    }
    private void handle(Socket initial) throws IOException {
        Socket socket = initial; BufferedReader in = reader(socket); OutputStream out = socket.getOutputStream(); line(out, "* OK fixture ready");
        for (String command; (command = in.readLine()) != null;) {
            String[] parts = command.split(" ", 3); if (parts.length < 2) return;
            String tag = parts[0], verb = parts[1].toUpperCase(Locale.ROOT);
            if (verb.equals("CAPABILITY")) { line(out, "* CAPABILITY IMAP4rev1" + (advertiseStartTls && !upgraded.get() ? " STARTTLS" : "") + " AUTH=PLAIN"); line(out, tag + " OK CAPABILITY completed"); }
            else if (verb.equals("STARTTLS")) {
                if (!advertiseStartTls || !acceptStartTls) { line(out, tag + " NO STARTTLS unavailable"); continue; }
                line(out, tag + " OK Begin TLS negotiation");
                try { SSLContext context = DeterministicImapFixture.serverContextForTests(); socket = context.getSocketFactory().createSocket(socket, "localhost", port(), false); ((SSLSocket) socket).setUseClientMode(false); ((SSLSocket) socket).startHandshake(); upgraded.set(true); in = reader(socket); out = socket.getOutputStream(); }
                catch (Exception failure) { throw new IOException("TLS upgrade failed", failure); }
            } else if (verb.equals("LOGIN")) { credentialCommands.incrementAndGet(); line(out, tag + (authenticationFailure == null ? " OK LOGIN completed" : " NO " + authenticationFailure)); }
            else if (verb.equals("AUTHENTICATE")) { credentialCommands.incrementAndGet(); line(out, "+"); if (in.readLine() == null) return; line(out, tag + (authenticationFailure == null ? " OK AUTHENTICATE completed" : " NO " + authenticationFailure)); }
            else if (verb.equals("LIST")) { line(out, "* LIST () \"/\" \"INBOX\""); line(out, tag + " OK LIST completed"); }
            else if (verb.equals("EXAMINE") || verb.equals("SELECT")) { line(out, "* 0 EXISTS"); line(out, "* OK [UIDVALIDITY 1] UIDs valid"); line(out, tag + " OK " + verb + " completed"); }
            else if (verb.equals("UID") && parts.length == 3 && parts[2].toUpperCase(Locale.ROOT).startsWith("SEARCH")) { line(out, "* SEARCH"); line(out, tag + " OK SEARCH completed"); }
            else if (verb.equals("CLOSE") || verb.equals("UNSELECT")) { closeCommands.incrementAndGet(); line(out, tag + " OK " + verb + " completed"); }
            else if (verb.equals("LOGOUT")) { logoutCommands.incrementAndGet(); line(out, "* BYE logging out"); line(out, tag + " OK LOGOUT completed"); return; }
            else line(out, tag + " OK completed");
        }
    }
    private static BufferedReader reader(Socket socket) throws IOException { return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)); }
    private static void line(OutputStream out, String value) throws IOException { out.write((value + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush(); }
    public ai.ravenroot.api.node.NodeAction action(ai.ravenroot.api.security.CredentialResolver credentials) throws Exception {
        var socketFactory = DeterministicImapFixture.trustedSocketFactoryForTests();
        return new MailImapQueryNodeBehavior((tenant, name) -> java.util.Optional.of(new ImapProfile(tenant, name, "localhost", port(), "STARTTLS", "reader", "credential", java.util.Set.of("INBOX"), 1_000, 1_000, 1, 1, 10)), credentials,
                properties -> { properties.put("mail.imap.ssl.socketFactory", socketFactory); return properties; })
                .create(new ai.ravenroot.api.node.NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR, java.util.Map.of("profile", "reader", "limit", "1")));
    }
    @Override public void close() throws Exception { running.set(false); listener.close(); worker.join(2_000); if (worker.isAlive()) throw new IllegalStateException("IMAP fixture worker did not terminate"); }
}
