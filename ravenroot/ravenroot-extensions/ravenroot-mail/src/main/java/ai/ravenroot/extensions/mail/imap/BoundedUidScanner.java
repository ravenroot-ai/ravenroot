package ai.ravenroot.extensions.mail.imap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Package-private traversal of bounded IMAP UID windows. */
final class BoundedUidScanner {
    private BoundedUidScanner() { }

    interface Mailbox<T> {
        List<T> fetch(long firstUid, long lastUid) throws Exception;
        List<T> search(List<T> candidates) throws Exception;
        long uid(T item) throws Exception;
    }

    @FunctionalInterface
    interface Match<T> {
        /** Returns {@code false} when this match proves that another result page exists. */
        boolean accept(T item, long uid) throws Exception;
    }

    static <T> boolean scan(long nextUid, long upperUid, int windowSize, int scanLimit,
                            int maxWindows, Mailbox<T> mailbox, Match<T> match,
                            Runnable deadlineCheck) throws Exception {
        int scanned = 0;
        int windows = 0;
        while (nextUid <= upperUid) {
            deadlineCheck.run();
            if (++windows > Math.min(maxWindows, scanLimit)) throw resourceLimit();
            long windowEnd = Math.min(upperUid, nextUid > Long.MAX_VALUE - (windowSize - 1L)
                    ? Long.MAX_VALUE : nextUid + windowSize - 1L);
            List<T> candidates = mailbox.fetch(nextUid, windowEnd);
            scanned += candidates.size();
            if (scanned > scanLimit) throw resourceLimit();

            List<T> found = new ArrayList<>(mailbox.search(candidates));
            Map<T, Long> uids = new IdentityHashMap<>();
            for (T item : found) uids.put(item, mailbox.uid(item));
            found.sort(Comparator.comparingLong(uids::get));
            for (T item : found) {
                deadlineCheck.run();
                long uid = uids.get(item);
                if (uid < nextUid || uid > windowEnd) continue;
                if (!match.accept(item, uid)) return true;
            }
            if (windowEnd == Long.MAX_VALUE) break;
            nextUid = windowEnd + 1;
        }
        return false;
    }

    private static ImapQueryException resourceLimit() {
        return new ImapQueryException(ImapQueryException.Code.RESOURCE_LIMIT,
                "IMAP query exceeds processing limits");
    }
}
