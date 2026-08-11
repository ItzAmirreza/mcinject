package dev.mcinject.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Breadth-first search over the live game heap.
 *
 * <p>This is the mapping-free way in. Obfuscation hides every class and field name, but it cannot
 * hide the data: a menu still contains its button labels as literal text, the player's name is still
 * a String, and health is still a float. Search for the value you recognise, get back a path and a
 * handle, and from there {@link Refl} can read or call anything nearby.
 *
 * <p>BFS rather than DFS matters: the interesting objects sit a few hops from the connection, while
 * the world/chunk graph is effectively unbounded. Breadth-first plus a node budget finds the
 * shallow answer before the budget drains into terrain data.
 */
public final class HeapSearch {

    private static final String[] SKIP_PREFIXES = {
            "java.lang.Class", "java.lang.ClassLoader", "java.lang.Module", "java.lang.reflect.",
            "java.lang.invoke.", "java.security.", "jdk.internal.", "sun.", "java.util.logging.",
            "org.apache.logging.", "java.lang.ThreadGroup", "java.io.File", "com.mojang.authlib.minecraft.client",
    };

    public static final class Query {
        public String contains;        // substring match, case-insensitive
        public String regex;           // alternative to contains
        public String ofClass;         // match objects whose class name contains this
        public int maxDepth = 12;
        public int maxNodes = 400_000;
        public int maxResults = 40;
        public long maxMillis = 8_000;
        public boolean includePath = true;

        public static Query of(Map<String, Object> m) {
            Query q = new Query();
            q.contains = Json.str(m, "contains", null);
            q.regex = Json.str(m, "regex", null);
            q.ofClass = Json.str(m, "ofClass", null);
            q.maxDepth = Json.i(m, "depth", q.maxDepth);
            q.maxNodes = Json.i(m, "maxNodes", q.maxNodes);
            q.maxResults = Json.i(m, "maxResults", q.maxResults);
            q.maxMillis = Json.i(m, "maxMillis", (int) q.maxMillis);
            return q;
        }
    }

    public static Map<String, Object> search(List<Object> roots, Query q) {
        long start = System.currentTimeMillis();
        Pattern pattern = q.regex == null || q.regex.isEmpty() ? null : Pattern.compile(q.regex);
        String needle = q.contains == null ? null : q.contains.toLowerCase(Locale.ROOT);
        String classNeedle = q.ofClass == null || q.ofClass.isEmpty() ? null : q.ofClass.toLowerCase(Locale.ROOT);

        List<Map<String, Object>> results = new ArrayList<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Node> queue = new ArrayDeque<>();
        for (int i = 0; i < roots.size(); i++) {
            Object r = roots.get(i);
            if (r != null) queue.add(new Node(r, 0, "root[" + i + "]", null));
        }

        int visited = 0;
        boolean budgetHit = false;
        while (!queue.isEmpty()) {
            if (visited >= q.maxNodes || results.size() >= q.maxResults
                    || System.currentTimeMillis() - start > q.maxMillis) {
                budgetHit = !queue.isEmpty();
                break;
            }
            Node n = queue.poll();
            Object o = n.value;
            if (o == null) continue;
            visited++;

            if (o instanceof CharSequence cs) {
                String s = cs.toString();
                if (matchesText(s, needle, pattern)) results.add(hit(n, s, "string"));
                continue;
            }
            if (o instanceof Enum<?> e) {
                if (matchesText(e.name(), needle, pattern)) results.add(hit(n, e.name(), "enum"));
                continue;
            }
            if (Introspect.isScalar(o)) {
                if (needle != null && pattern == null && String.valueOf(o).equalsIgnoreCase(q.contains)) {
                    results.add(hit(n, String.valueOf(o), "scalar"));
                }
                continue;
            }
            if (seen.put(o, Boolean.TRUE) != null) continue;

            Class<?> c = o.getClass();
            String cn = c.getName();
            if (skip(cn)) continue;

            if (classNeedle != null && cn.toLowerCase(Locale.ROOT).contains(classNeedle)) {
                results.add(hit(n, Introspect.summary(o), "instance"));
            }
            if (n.depth >= q.maxDepth) continue;

            try {
                if (c.isArray()) {
                    if (c.getComponentType().isPrimitive()) continue;
                    int len = Math.min(Array.getLength(o), 4096);
                    for (int i = 0; i < len; i++) {
                        queue.add(new Node(Array.get(o, i), n.depth + 1, n.path + "[" + i + "]", o));
                    }
                } else if (o instanceof Collection<?> col) {
                    int i = 0;
                    for (Object e : new ArrayList<>(col)) {
                        queue.add(new Node(e, n.depth + 1, n.path + "[" + i++ + "]", o));
                        if (i > 4096) break;
                    }
                } else if (o instanceof Map<?, ?> m) {
                    int i = 0;
                    for (Map.Entry<?, ?> e : new ArrayList<>(m.entrySet())) {
                        queue.add(new Node(e.getKey(), n.depth + 1, n.path + "{key" + i + "}", o));
                        queue.add(new Node(e.getValue(), n.depth + 1, n.path + "{" + safeKey(e.getKey()) + "}", o));
                        if (++i > 4096) break;
                    }
                } else {
                    for (Field f : Introspect.fieldsOf(c, false)) {
                        if (f.getType().isPrimitive()) continue;
                        Object v;
                        try { v = f.get(o); } catch (Throwable t) { continue; }
                        if (v != null) queue.add(new Node(v, n.depth + 1, n.path + "." + f.getName(), o));
                    }
                }
            } catch (Throwable ignored) {
                // the game mutates these structures underneath us; skipping a node is fine
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("visited", visited);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        out.put("truncated", budgetHit || results.size() >= q.maxResults);
        return out;
    }

    private static boolean matchesText(String s, String needle, Pattern pattern) {
        if (pattern != null) return pattern.matcher(s).find();
        if (needle == null) return false;
        return s.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Map<String, Object> hit(Node n, String value, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("path", n.path);
        m.put("value", Introspect.clip(value, 300));
        m.put("depth", n.depth);
        if (n.owner != null) {
            m.put("ownerClass", n.owner.getClass().getName());
            m.put("owner", Handles.of(n.owner));
        }
        if (!Introspect.isScalar(n.value)) m.put("handle", Handles.of(n.value));
        return m;
    }

    private static String safeKey(Object k) {
        String s = String.valueOf(k);
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static boolean skip(String cn) {
        for (String p : SKIP_PREFIXES) if (cn.startsWith(p)) return true;
        return false;
    }

    /** Where searches start when the caller doesn't say: the live connection, then the channels. */
    public static List<Object> defaultRoots() {
        List<Object> roots = new ArrayList<>();
        Object primary = TapManager.primaryChannel();
        if (primary != null) roots.add(primary);
        for (Object ch : Discovery.findGameChannels()) if (!roots.contains(ch)) roots.add(ch);
        if (roots.isEmpty()) roots.addAll(Discovery.findChannels());
        return roots;
    }

    /** Static fields of every loaded class matching a package prefix — the widest useful root set. */
    public static List<Object> staticRoots(String packagePrefix, int maxClasses) {
        List<Object> roots = new ArrayList<>();
        Class<?>[] loaded = AgentMain.loadedClasses();
        if (loaded == null) return roots;
        int n = 0;
        for (Class<?> c : loaded) {
            if (!c.getName().startsWith(packagePrefix)) continue;
            if (++n > maxClasses) break;
            for (Field f : Introspect.fieldsOf(c, true)) {
                try {
                    Object v = f.get(null);
                    if (v != null && !Introspect.isScalar(v)) roots.add(v);
                } catch (Throwable ignored) {
                    // uninitialised or inaccessible statics are simply not roots
                }
            }
        }
        return roots;
    }

    private record Node(Object value, int depth, String path, Object owner) {}

    private HeapSearch() {}
}
