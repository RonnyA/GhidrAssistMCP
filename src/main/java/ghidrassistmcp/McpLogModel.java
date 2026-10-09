package ghidrassistmcp;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Bounded application log. Views subscribe to snapshots and never own its lifetime. */
public final class McpLogModel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final int maxEntries;
    private final Deque<String> entries = new ArrayDeque<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public McpLogModel(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    public synchronized void append(String message) {
        entries.addLast("[" + TIME.format(LocalTime.now()) + "] " + message + "\n");
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
        publish();
    }

    public synchronized void clear() {
        entries.clear();
        publish();
    }

    public synchronized String snapshot() {
        return String.join("", entries);
    }

    /** Initial snapshot and subscription are atomic with respect to new entries. */
    public synchronized void subscribe(Consumer<String> listener) {
        listeners.add(listener);
        listener.accept(snapshot());
    }

    public synchronized void unsubscribe(Consumer<String> listener) {
        listeners.remove(listener);
    }

    private void publish() {
        String snapshot = snapshot();
        for (Consumer<String> listener : listeners) {
            listener.accept(snapshot);
        }
    }

    static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String singleLine = text.replace("\n", "\\n");
        return singleLine.length() > 240 ? singleLine.substring(0, 237) + "..." : singleLine;
    }
}
