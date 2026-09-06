package ai.ravenroot.extensions.mail.imap;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedUidScannerTest {
    @Test
    void scanLimitClassifiesBeforeSearchingOrMappingBeyondTheBoundAndNarrowerRangeRecovers()
            throws Exception {
        Item first = new Item(1, false);
        Item second = new Item(2, false);
        Item target = new Item(3, true);
        var mailbox = new ControlledMailbox(List.of(first, second, target));
        List<Long> visited = new ArrayList<>();

        ImapQueryException bounded = assertThrows(ImapQueryException.class,
                () -> BoundedUidScanner.scan(1, 3, 2, 2, 2, mailbox,
                        (item, uid) -> visited.add(uid), () -> { }));

        assertEquals(ImapQueryException.Code.RESOURCE_LIMIT, bounded.code());
        assertEquals(List.of(1L, 2L), mailbox.searchedUids,
                "the over-budget window must not reach server search");
        assertEquals(List.of(), visited, "the over-budget target must not reach row mapping");

        mailbox.searchedUids.clear();
        assertFalse(BoundedUidScanner.scan(3, 3, 2, 2, 2, mailbox,
                (item, uid) -> visited.add(uid), () -> { }));
        assertEquals(List.of(3L), mailbox.searchedUids);
        assertEquals(List.of(3L), visited);
    }

    @Test
    void resultLimitUsesUidOrderAndReportsAnotherPageWithoutVisitingTheExtraMatch()
            throws Exception {
        Item second = new Item(2, true);
        Item first = new Item(1, true);
        var mailbox = new ControlledMailbox(List.of(second, first));
        List<Long> visited = new ArrayList<>();

        boolean hasMore = BoundedUidScanner.scan(1, 2, 2, 2, 2, mailbox,
                (item, uid) -> {
                    if (visited.size() == 1) return false;
                    visited.add(uid);
                    return true;
                }, () -> { });

        assertTrue(hasMore);
        assertEquals(List.of(1L), visited);
    }

    @Test
    void sparseMailboxStopsAtTheWindowCap() {
        var mailbox = new ControlledMailbox(List.of());

        ImapQueryException bounded = assertThrows(ImapQueryException.class,
                () -> BoundedUidScanner.scan(1, 5, 1, 5, 2, mailbox,
                        (item, uid) -> true, () -> { }));

        assertEquals(ImapQueryException.Code.RESOURCE_LIMIT, bounded.code());
        assertEquals(List.of("1-1", "2-2"), mailbox.fetchedWindows);
    }

    @Test
    void finalUidWindowDoesNotOverflow() throws Exception {
        var mailbox = new ControlledMailbox(List.of());

        assertFalse(BoundedUidScanner.scan(Long.MAX_VALUE - 1, Long.MAX_VALUE, 128, 2, 2,
                mailbox, (item, uid) -> true, () -> { }));

        assertEquals(List.of((Long.MAX_VALUE - 1) + "-" + Long.MAX_VALUE), mailbox.fetchedWindows);
    }

    private record Item(long uid, boolean matches) { }

    private static final class ControlledMailbox implements BoundedUidScanner.Mailbox<Item> {
        private final List<Item> items;
        private final List<String> fetchedWindows = new ArrayList<>();
        private final List<Long> searchedUids = new ArrayList<>();

        private ControlledMailbox(List<Item> items) {
            this.items = items;
        }

        @Override
        public List<Item> fetch(long firstUid, long lastUid) {
            fetchedWindows.add(firstUid + "-" + lastUid);
            return items.stream()
                    .filter(item -> item.uid >= firstUid && item.uid <= lastUid)
                    .toList();
        }

        @Override
        public List<Item> search(List<Item> candidates) {
            searchedUids.addAll(candidates.stream().map(Item::uid).toList());
            return candidates.stream().filter(Item::matches).toList();
        }

        @Override
        public long uid(Item item) {
            return item.uid;
        }
    }
}
