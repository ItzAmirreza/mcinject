package dev.mcinject.agent;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable string IDs for live game objects, so an LLM can say "read field X of h42" across separate
 * HTTP calls. Handles pin their objects, so the table is LRU-bounded to keep the game's heap sane.
 */
public final class Handles {
    private static final int MAX = 20_000;

    private static final IdentityHashMap<Object, String> byObject = new IdentityHashMap<>();
    private static final LinkedHashMap<String, Object> byId =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    if (size() <= MAX) return false;
                    byObject.remove(eldest.getValue());
                    return true;
                }
            };
    private static long seq = 0;

    /** Returns null for null, so callers can pass field values straight through. */
    public static synchronized String of(Object o) {
        if (o == null) return null;
        String id = byObject.get(o);
        if (id != null) {
            byId.get(id); // touch for LRU
            return id;
        }
        id = "h" + (++seq);
        byObject.put(o, id);
        byId.put(id, o);
        return id;
    }

    public static synchronized Object get(String id) {
        return id == null ? null : byId.get(id);
    }

    public static synchronized Object require(String id) {
        Object o = get(id);
        if (o == null) throw new IllegalArgumentException("unknown or expired handle: " + id);
        return o;
    }

    public static synchronized int size() { return byId.size(); }

    public static synchronized void clear() {
        byId.clear();
        byObject.clear();
    }

    private Handles() {}
}
