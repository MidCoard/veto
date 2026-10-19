package top.focess.veto.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecentHistoryTest {
    @Test
    void evictionPreservesTheNewestEntriesAndExistingSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> new RecentHistory<>(0));
        var history = new RecentHistory<String>(2);
        history.add("first");
        history.add("second");
        var previous = history.snapshot();
        history.add("third");
        assertEquals(List.of("second", "third"), history.snapshot());
        assertEquals(List.of("first", "second"), previous);
        assertThrows(UnsupportedOperationException.class, () -> previous.add("outside mutation"));
    }
}
