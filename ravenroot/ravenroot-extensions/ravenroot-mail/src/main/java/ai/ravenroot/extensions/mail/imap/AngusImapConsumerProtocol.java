package ai.ravenroot.extensions.mail.imap;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.util.function.UnaryOperator;

/** Angus Mail IMAPS/required-STARTTLS polling owner with read-only, peeked folders. */
final class AngusImapConsumerProtocol implements ImapConsumerProtocol {
    static final int MAX_IO_TIMEOUT_MS = 30_000;
    private final UnaryOperator<Properties> propertyCustomizer;

    AngusImapConsumerProtocol() { this(UnaryOperator.identity()); }

    AngusImapConsumerProtocol(UnaryOperator<Properties> propertyCustomizer) {
        this.propertyCustomizer = java.util.Objects.requireNonNull(propertyCustomizer);
    }

    @Override public Owner open(ImapProfile profile, String folderName, char[] password, Opening opening)
            throws Failure {
        Store store = null;
        Folder folder = null;
        try {
            String protocol = profile.securityMode().equals("IMAPS") ? "imaps" : "imap";
            Properties properties = java.util.Objects.requireNonNull(
                    propertyCustomizer.apply(properties(profile, protocol)));
            trackSockets(properties, protocol, opening);
            Session session = Session.getInstance(properties);
            store = session.getStore(protocol);
            if (opening.cancelled()) throw new Failure(false, "imap-startup-cancelled");
            store.connect(profile.host(), profile.port(), profile.username(), new String(password));
            if (opening.cancelled()) throw new Failure(false, "imap-startup-cancelled");
            folder = store.getFolder(folderName);
            if (!folder.exists()) throw new Failure(true, "imap-folder-unavailable");
            folder.open(Folder.READ_ONLY);
            if (opening.cancelled()) throw new Failure(false, "imap-startup-cancelled");
            if (!(folder instanceof IMAPFolder imap) || !(folder instanceof UIDFolder))
                throw new Failure(true, "imap-uid-required");
            if (!folderName.equals(folder.getFullName()))
                throw new Failure(true, "imap-folder-not-canonical");
            long validity = imap.getUIDValidity();
            requireUnsigned32(validity, "imap-uidvalidity-invalid");
            return new AngusOwner(store, imap, folderName, validity, opening);
        } catch (Failure failure) {
            opening.cancel();
            throw failure;
        } catch (AuthenticationFailedException denied) {
            opening.cancel();
            throw new Failure(true, "imap-authentication-refused");
        } catch (MessagingException transport) {
            opening.cancel();
            throw new Failure(false, "imap-transport-unavailable");
        } catch (RuntimeException failure) {
            opening.cancel();
            throw new Failure(false, "imap-transport-unavailable");
        }
    }

    private static void trackSockets(Properties properties, String protocol, Opening opening) {
        String prefix = "mail." + protocol;
        Object configuredSsl = properties.get(prefix + ".ssl.socketFactory");
        SSLSocketFactory ssl = configuredSsl instanceof SSLSocketFactory factory
                ? factory : (SSLSocketFactory) SSLSocketFactory.getDefault();
        properties.put(prefix + ".ssl.socketFactory", new TrackingSslSocketFactory(ssl, opening));
        if (protocol.equals("imap")) {
            Object configuredPlain = properties.get(prefix + ".socketFactory");
            SocketFactory plain = configuredPlain instanceof SocketFactory factory
                    ? factory : SocketFactory.getDefault();
            properties.put(prefix + ".socketFactory", new TrackingSocketFactory(plain, opening));
        }
    }

    static Properties properties(ImapProfile profile, String protocol) {
        String prefix = "mail." + protocol;
        Properties properties = new Properties();
        properties.setProperty(prefix + ".ssl.checkserveridentity", "true");
        properties.setProperty(prefix + ".connectiontimeout",
                Integer.toString(Math.min(MAX_IO_TIMEOUT_MS, profile.connectTimeoutMs())));
        properties.setProperty(prefix + ".timeout",
                Integer.toString(Math.min(MAX_IO_TIMEOUT_MS, profile.readTimeoutMs())));
        properties.setProperty(prefix + ".writetimeout",
                Integer.toString(Math.min(MAX_IO_TIMEOUT_MS, profile.readTimeoutMs())));
        properties.setProperty(prefix + ".peek", "true");
        if (protocol.equals("imap")) {
            properties.setProperty("mail.imap.starttls.enable", "true");
            properties.setProperty("mail.imap.starttls.required", "true");
        }
        return properties;
    }

    private static final class AngusOwner implements Owner {
        private final Store store;
        private final IMAPFolder folder;
        private final String sourceFolder;
        private final long initialValidity;
        private final Opening opening;
        private final AtomicBoolean closed = new AtomicBoolean();

        AngusOwner(Store store, IMAPFolder folder, String sourceFolder, long initialValidity, Opening opening) {
            this.store = store; this.folder = folder; this.sourceFolder = sourceFolder;
            this.initialValidity = initialValidity; this.opening = opening;
        }

        @Override public String sourceFolder() { return sourceFolder; }
        @Override public long uidValidity() { return initialValidity; }

        @Override public Poll pollAfter(long afterUid, int batchSize, int scanWindow)
                throws Failure {
            if (closed.get()) throw new Failure(false, "imap-session-closed");
            try {
                // NOOP is the standards-defined polling operation for a selected mailbox. Angus
                // processes EXISTS/EXPUNGE responses before returning, refreshing message-number
                // state without the prohibited/expensive STATUS-on-selected-mailbox pattern.
                folder.doCommand(protocol -> { protocol.noop(); return null; });
                long validity = folder.getUIDValidity();
                requireUnsigned32(validity, "imap-uidvalidity-invalid");
                int messageCount = folder.getMessageCount();
                if (messageCount < 0) throw new Failure(false, "imap-message-count-unavailable");
                long upper = 0;
                if (messageCount > 0) {
                    upper = folder.getUID(folder.getMessage(messageCount));
                    requireUnsigned32(upper, "imap-uid-invalid");
                }
                if (afterUid >= upper) return new Poll(validity, afterUid, List.of());
                long start = Math.max(1, afterUid + 1);
                long end = Math.min(upper, start + scanWindow - 1L);
                Message[] fetched = folder.getMessagesByUID(start, end);
                Map<Message, Long> uids = new IdentityHashMap<>();
                for (Message message : fetched) {
                    long uid = folder.getUID(message);
                    requireUnsigned32(uid, "imap-uid-invalid");
                    uids.put(message, uid);
                }
                Arrays.sort(fetched, Comparator.comparingLong(uids::get));
                List<Item> items = new ArrayList<>(Math.min(batchSize, fetched.length));
                for (Message message : fetched) {
                    if (items.size() == batchSize) break;
                    items.add(new Item(uids.get(message), message));
                }
                long scanned = fetched.length > batchSize ? items.getLast().uid() : end;
                return new Poll(validity, scanned, items);
            } catch (MessagingException failure) {
                throw new Failure(false, "imap-transport-disconnected");
            } catch (RuntimeException failure) {
                throw new Failure(false, "imap-transport-disconnected");
            }
        }

        @Override public void wakeup() { opening.cancel(); }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            // Closing every tracked socket is the deterministic bound. Provider LOGOUT/close methods
            // are deliberately not called here: a hostile or wedged provider may block beyond the
            // graph's stop deadline even after its socket is revoked. Releasing the only transport
            // resource first makes this operation synchronous and independent of provider progress.
            opening.cancel();
        }
    }

    private static void requireUnsigned32(long value, String reason) throws Failure {
        if (value < 1 || value > 0xffff_ffffL) throw new Failure(true, reason);
    }

    private static class TrackingSocketFactory extends SocketFactory {
        private final SocketFactory delegate;
        private final Opening opening;
        TrackingSocketFactory(SocketFactory delegate, Opening opening) {
            this.delegate = delegate; this.opening = opening;
        }
        @Override public Socket createSocket() throws java.io.IOException {
            return opening.track(delegate.createSocket());
        }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException {
            return opening.track(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws java.io.IOException { return opening.track(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return opening.track(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
                throws java.io.IOException { return opening.track(delegate.createSocket(host, port, local, localPort)); }
    }

    private static final class TrackingSslSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final Opening opening;
        TrackingSslSocketFactory(SSLSocketFactory delegate, Opening opening) {
            this.delegate = delegate; this.opening = opening;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws java.io.IOException {
            return opening.track(delegate.createSocket());
        }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException {
            return opening.track(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws java.io.IOException { return opening.track(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return opening.track(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
                throws java.io.IOException { return opening.track(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close)
                throws java.io.IOException { return opening.track(delegate.createSocket(socket, host, port, close)); }
    }
}
