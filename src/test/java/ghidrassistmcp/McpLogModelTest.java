package ghidrassistmcp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class McpLogModelTest {
    @Test void newViewsReplayHistoryAndBothViewsFollowSharedClear() {
        McpLogModel log = new McpLogModel(2);
        log.append("before views");
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        Consumer<String> firstView = first::add;
        log.subscribe(firstView);
        log.subscribe(second::add);
        assertEquals(first.getLast(), second.getLast());
        assertTrue(second.getLast().contains("before views"));
        log.append("first");
        log.append("second");
        assertEquals(first.getLast(), second.getLast());
        assertFalse(second.getLast().contains("before views"));
        log.unsubscribe(firstView);
        int closedViewUpdates = first.size();
        log.clear();
        assertEquals("", second.getLast());
        assertEquals(closedViewUpdates, first.size());
    }

    @Test void requestLengthsThatPreviouslyFailedRemainLoggable() {
        for (int length = 60; length <= 80; length++) {
            String request = "a".repeat(length);
            assertEquals(request, McpLogModel.abbreviate(request));
        }
        assertEquals(240, McpLogModel.abbreviate("a".repeat(400)).length());
        assertEquals("", McpLogModel.abbreviate(null));
        assertEquals("a\\nb", McpLogModel.abbreviate("a\nb"));
    }
}
