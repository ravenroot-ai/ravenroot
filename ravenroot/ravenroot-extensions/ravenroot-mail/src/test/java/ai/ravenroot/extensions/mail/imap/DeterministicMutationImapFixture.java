package ai.ravenroot.extensions.mail.imap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLServerSocket;

/** Minimal trusted IMAPS server that fails only after accepting a UID MOVE command. */
final class DeterministicMutationImapFixture implements AutoCloseable {
    enum Failure { DISCONNECT, SLOW_RESPONSE, UIDVALIDITY_ROLLOVER }

    private final SSLServerSocket listener;
    private final Failure failure;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch mutation = new CountDownLatch(1);
    private final CountDownLatch socketClosed = new CountDownLatch(1);
    private final List<String> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger selections = new AtomicInteger();
    private final Thread worker;

    DeterministicMutationImapFixture(Failure failure) throws Exception {
        this.failure = failure;
        listener = (SSLServerSocket) DeterministicImapFixture.serverContextForTests()
                .getServerSocketFactory().createServerSocket(
                        0, 10, InetAddress.getByName("127.0.0.1"));
        worker = new Thread(this::serve, "deterministic-mutation-imap");
        worker.setDaemon(true);
        worker.start();
    }

    int port() { return listener.getLocalPort(); }
    boolean awaitMutation() throws InterruptedException { return mutation.await(2, TimeUnit.SECONDS); }
    boolean awaitSocketClose() throws InterruptedException {
        return socketClosed.await(2, TimeUnit.SECONDS);
    }
    List<String> commands() { return List.copyOf(commands); }

    private void serve() {
        try (Socket socket = listener.accept()) {
            handle(socket);
        } catch (IOException ignored) { }
        finally { socketClosed.countDown(); }
    }

    private void handle(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));
        OutputStream out = socket.getOutputStream();
        line(out, "* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN MOVE UIDPLUS] fixture ready");
        for (String command; (command = in.readLine()) != null;) {
            commands.add(command);
            String[] parts = command.split(" ", 3);
            if (parts.length < 2) return;
            String tag = parts[0];
            String verb = parts[1].toUpperCase(Locale.ROOT);
            String remainder = parts.length == 3 ? parts[2].toUpperCase(Locale.ROOT) : "";
            if (verb.equals("CAPABILITY")) {
                line(out, "* CAPABILITY IMAP4rev1 AUTH=PLAIN MOVE UIDPLUS");
                line(out, tag + " OK CAPABILITY completed");
            } else if (verb.equals("LOGIN")) {
                line(out, tag + " OK LOGIN completed");
            } else if (verb.equals("AUTHENTICATE")) {
                line(out, "+");
                if (in.readLine() == null) return;
                line(out, tag + " OK AUTHENTICATE completed");
            } else if (verb.equals("SELECT") || verb.equals("EXAMINE")) {
                boolean rolledOver = failure == Failure.UIDVALIDITY_ROLLOVER
                        && selections.incrementAndGet() > 1;
                line(out, rolledOver ? "* 0 EXISTS" : "* 1 EXISTS");
                line(out, "* OK [UIDVALIDITY " + (rolledOver ? 8 : 7) + "] UIDs valid");
                line(out, "* OK [UIDNEXT " + (rolledOver ? 1 : 2) + "] Predicted next UID");
                line(out, tag + " OK [READ-WRITE] SELECT completed");
            } else if (verb.equals("LIST")) {
                line(out, "* LIST () \"/\" \"Archive\"");
                line(out, tag + " OK LIST completed");
            } else if (verb.equals("UID") && remainder.startsWith("FETCH")) {
                if (failure != Failure.UIDVALIDITY_ROLLOVER || selections.get() <= 1)
                    line(out, "* 1 FETCH (UID 1)");
                line(out, tag + " OK UID FETCH completed");
            } else if (verb.equals("MOVE") || verb.equals("UID") && remainder.startsWith("MOVE")) {
                mutation.countDown();
                if (failure == Failure.DISCONNECT) {
                    line(out, "* BYE fixture disconnect after mutation command");
                    socket.setSoLinger(true, 0);
                    return;
                }
                if (failure == Failure.UIDVALIDITY_ROLLOVER) {
                    line(out, tag + " OK [COPYUID 9 1 4] MOVE completed");
                    continue;
                }
                byte[] response = (tag + " OK [COPYUID 9 1 4] MOVE completed\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
                for (byte value : response) {
                    out.write(value);
                    out.flush();
                    try { Thread.sleep(150); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } else if (verb.equals("CLOSE") || verb.equals("UNSELECT")) {
                line(out, tag + " OK " + verb + " completed");
            } else if (verb.equals("LOGOUT")) {
                line(out, "* BYE logging out");
                line(out, tag + " OK LOGOUT completed");
                return;
            } else {
                line(out, tag + " OK completed");
            }
        }
    }

    private static void line(OutputStream out, String value) throws IOException {
        out.write((value + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override public void close() throws Exception {
        running.set(false);
        listener.close();
        worker.interrupt();
        worker.join(2_000);
        if (worker.isAlive())
            throw new IllegalStateException("Mutation IMAP fixture worker did not terminate");
    }
}
