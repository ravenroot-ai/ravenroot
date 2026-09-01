package ai.ravenroot.extensions.mail.imap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLServerSocket;

/** Trusted IMAPS fixture whose first UID response makes progress without ever completing. */
final class DeterministicSlowDripImapFixture implements AutoCloseable {
    private static final long DRIP_INTERVAL_MS = 150;
    private final SSLServerSocket listener;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean slowResponseClaimed = new AtomicBoolean();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger dripWrites = new AtomicInteger();
    private final AtomicInteger socketsClosed = new AtomicInteger();
    private final CountDownLatch slowCommand = new CountDownLatch(1);
    private final CountDownLatch firstSocketClosed = new CountDownLatch(1);
    private final Thread worker;

    DeterministicSlowDripImapFixture() throws Exception {
        listener = (SSLServerSocket) DeterministicImapFixture.serverContextForTests().getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        worker = new Thread(this::serve, "deterministic-slow-drip-imap");
        worker.setDaemon(true);
        worker.start();
    }

    int port() { return listener.getLocalPort(); }
    int dripWrites() { return dripWrites.get(); }
    int socketsClosed() { return socketsClosed.get(); }
    boolean awaitSlowCommand() throws InterruptedException { return slowCommand.await(2, TimeUnit.SECONDS); }
    boolean awaitFirstSocketClose() throws InterruptedException { return firstSocketClosed.await(2, TimeUnit.SECONDS); }

    private void serve() {
        while (running.get()) try {
            Socket socket = listener.accept();
            int connection = connections.incrementAndGet();
            try (socket) { handle(socket, connection); }
            catch (IOException ignored) { }
            finally { socketsClosed.incrementAndGet(); if (connection == 1) firstSocketClosed.countDown(); }
        } catch (IOException ignored) { if (!running.get()) return; }
    }

    private void handle(Socket socket, int connection) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        OutputStream out = socket.getOutputStream();
        line(out, "* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] fixture ready");
        for (String command; (command = in.readLine()) != null;) {
            String[] parts = command.split(" ", 3);
            if (parts.length < 2) return;
            String tag = parts[0], verb = parts[1].toUpperCase(Locale.ROOT);
            if (verb.equals("CAPABILITY")) {
                line(out, "* CAPABILITY IMAP4rev1 AUTH=PLAIN"); line(out, tag + " OK CAPABILITY completed");
            } else if (verb.equals("LOGIN")) line(out, tag + " OK LOGIN completed");
            else if (verb.equals("AUTHENTICATE")) {
                line(out, "+"); if (in.readLine() == null) return; line(out, tag + " OK AUTHENTICATE completed");
            } else if (verb.equals("EXAMINE") || verb.equals("SELECT")) {
                int exists = connection == 1 ? 1 : 0;
                line(out, "* " + exists + " EXISTS");
                line(out, "* OK [UIDVALIDITY 1] UIDs valid");
                line(out, "* OK [UIDNEXT " + (exists + 1) + "] Predicted next UID");
                line(out, tag + " OK " + verb + " completed");
            } else if (verb.equals("UID") && slowResponseClaimed.compareAndSet(false, true)) {
                slowCommand.countDown();
                byte[] response = ("* SEARCH 1\r\n" + tag + " OK UID completed\r\n").getBytes(StandardCharsets.US_ASCII);
                for (byte value : response) {
                    out.write(value); out.flush(); dripWrites.incrementAndGet();
                    try { Thread.sleep(DRIP_INTERVAL_MS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            } else if (verb.equals("UID")) { line(out, "* SEARCH"); line(out, tag + " OK UID completed"); }
            else if (verb.equals("CLOSE") || verb.equals("UNSELECT")) line(out, tag + " OK " + verb + " completed");
            else if (verb.equals("LOGOUT")) { line(out, "* BYE logging out"); line(out, tag + " OK LOGOUT completed"); return; }
            else line(out, tag + " OK completed");
        }
    }

    private static void line(OutputStream out, String value) throws IOException {
        out.write((value + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
    }

    @Override public void close() throws Exception {
        running.set(false); listener.close(); worker.interrupt(); worker.join(2_000);
        if (worker.isAlive()) throw new IllegalStateException("Slow-drip IMAP fixture worker did not terminate");
    }
}
