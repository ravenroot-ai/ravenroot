package ai.ravenroot.extensions.mail.imap;

import jakarta.mail.Message;

import java.util.List;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-worker protocol seam; a live owner is never used by a callback thread. */
@FunctionalInterface
interface ImapConsumerProtocol {
    Owner open(ImapProfile profile, String folder, char[] password, Opening opening) throws Failure;

    interface Owner {
        String sourceFolder();
        long uidValidity();
        /** Provider I/O is bounded by the profile's fixed read timeout configured at open. */
        Poll pollAfter(long afterUid, int batchSize, int scanWindow) throws Failure;
        void wakeup();
        /** Immediately revokes the tracked transport; it never waits on provider LOGOUT/close. */
        void close();
    }

    /** Cancellable connect/open phase. Socket registration makes stop effective before an owner exists. */
    final class Opening {
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        Socket track(Socket socket) {
            if (cancelled.get()) close(socket);
            else {
                sockets.add(socket);
                if (cancelled.get() && sockets.remove(socket)) close(socket);
            }
            return socket;
        }

        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            sockets.forEach(Opening::close);
            sockets.clear();
        }

        boolean cancelled() { return cancelled.get(); }
        int trackedSockets() { return sockets.size(); }

        private static void close(Socket socket) {
            try { socket.shutdownInput(); } catch (Exception ignored) { }
            try { socket.shutdownOutput(); } catch (Exception ignored) { }
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    record Item(long uid, Message message) {
        public Item {
            if (uid < 1 || uid > 0xffff_ffffL || message == null)
                throw new IllegalArgumentException("invalid IMAP item");
        }
    }

    record Poll(long uidValidity, long scannedThrough, List<Item> items) {
        public Poll {
            if (uidValidity < 1 || uidValidity > 0xffff_ffffL || scannedThrough < 0
                    || scannedThrough > 0xffff_ffffL) throw new IllegalArgumentException("invalid IMAP poll");
            items = List.copyOf(items);
        }
    }

    final class Failure extends Exception {
        private final boolean permanent;
        Failure(boolean permanent, String safeReason) { super(safeReason); this.permanent = permanent; }
        boolean permanent() { return permanent; }
    }
}
