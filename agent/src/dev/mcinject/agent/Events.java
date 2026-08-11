package dev.mcinject.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded, sequence-numbered event log. Clients (the CLI, the MCP server) either long-poll
 * {@code /events?since=N} or hold open an SSE stream; both read from here, so a dropped connection
 * costs nothing but a resume from the last sequence it saw.
 */
public final class Events {

    public static final class Event {
        public final long seq;
        public final long ts;
        public final String json;

        Event(long seq, String json) {
            this.seq = seq;
            this.ts = System.currentTimeMillis();
            this.json = json;
        }
    }

    private static final int MAX = 2048;
    private static final Deque<Event> ring = new ArrayDeque<>();
    private static final AtomicLong seqGen = new AtomicLong();
    private static final Object lock = new Object();

    public static void push(String type, Map<String, Object> fields, String rawDataJson) {
        long s = seqGen.incrementAndGet();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", s);
        m.put("ts", System.currentTimeMillis());
        m.put("type", type);
        if (fields != null) m.putAll(fields);
        if (rawDataJson != null) m.put("data", new Json.Raw(rawDataJson));
        Event e = new Event(s, Json.write(m));
        synchronized (lock) {
            ring.addLast(e);
            while (ring.size() > MAX) ring.removeFirst();
            lock.notifyAll();
        }
    }

    public static void push(String type, Map<String, Object> fields) {
        push(type, fields, null);
    }

    public static List<Event> since(long seq) {
        synchronized (lock) {
            List<Event> out = new ArrayList<>();
            for (Event e : ring) if (e.seq > seq) out.add(e);
            return out;
        }
    }

    /** Blocks until an event newer than {@code seq} exists or the timeout expires. */
    public static List<Event> await(long seq, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (true) {
                List<Event> out = since(seq);
                if (!out.isEmpty()) return out;
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return List.of();
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return List.of();
                }
            }
        }
    }

    public static long head() { return seqGen.get(); }

    private Events() {}
}
