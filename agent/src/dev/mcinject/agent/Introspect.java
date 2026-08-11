package dev.mcinject.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns arbitrary live game objects into bounded JSON trees.
 *
 * <p>Minecraft's classes are often obfuscated (Fabric can leave them as
 * {@code net.minecraft.class_2813}), so names carry no meaning — but the <em>values</em> do. A caller
 * can read this tree, spot a menu label three levels down, and work out the slot index that holds it
 * without any mapping file.
 */
public final class Introspect {

    public static final class Opts {
        public int maxDepth = 6;
        public int maxNodes = 400;
        public int maxElems = 40;
        public int maxString = 300;
        public boolean statics = false;
        public boolean handles = true;

        public static Opts of(Map<String, ?> m) {
            Opts o = new Opts();
            o.maxDepth = Json.i(m, "depth", o.maxDepth);
            o.maxNodes = Json.i(m, "maxNodes", o.maxNodes);
            o.maxElems = Json.i(m, "maxElems", o.maxElems);
            o.maxString = Json.i(m, "maxString", o.maxString);
            o.statics = Json.bool(m, "statics", o.statics);
            o.handles = Json.bool(m, "handles", o.handles);
            return o;
        }
    }

    /** Classes we render as a plain string rather than descending into: descending explodes. */
    private static final String[] OPAQUE_PREFIXES = {
            "java.lang.Class", "java.lang.ClassLoader", "java.lang.Module", "java.lang.Thread",
            "java.lang.reflect.", "java.lang.invoke.", "java.security.", "jdk.internal.",
            "sun.", "java.util.concurrent.locks.", "java.io.File", "java.net.",
    };

    public static Object tree(Object root, Opts opts) {
        return new Walk(opts).node(root, 0);
    }

    /** One-line description: class name plus whatever scalar fields fit. Cheap enough for hot paths. */
    public static String summary(Object o) {
        if (o == null) return "null";
        Class<?> c = o.getClass();
        if (isScalar(o)) return String.valueOf(o);
        StringBuilder sb = new StringBuilder(simple(c));
        List<String> bits = new ArrayList<>();
        try {
            for (Field f : fieldsOf(c, false)) {
                if (bits.size() >= 5) break;
                Object v;
                try { v = f.get(o); } catch (Throwable t) { continue; }
                if (v != null && isScalar(v)) bits.add(f.getName() + "=" + clip(String.valueOf(v), 40));
            }
        } catch (Throwable ignored) {
            // best effort only
        }
        if (!bits.isEmpty()) sb.append('(').append(String.join(", ", bits)).append(')');
        return sb.toString();
    }

    /** Collects every string reachable from {@code root}, for the search endpoint. */
    public static void collectStrings(Object root, int maxDepth, int maxNodes, StringSink sink) {
        new Walk(depthOpts(maxDepth, maxNodes)).strings(root, 0, "", sink);
    }

    public interface StringSink {
        /** @return false to stop the walk early. */
        boolean accept(String path, String value, Object owner);
    }

    private static Opts depthOpts(int depth, int nodes) {
        Opts o = new Opts();
        o.maxDepth = depth;
        o.maxNodes = nodes;
        o.maxElems = 512;
        o.maxString = 400;
        return o;
    }

    // ---------------------------------------------------------------

    private static final class Walk {
        private final Opts opts;
        private final IdentityHashMap<Object, String> seen = new IdentityHashMap<>();
        private int nodes = 0;

        Walk(Opts opts) { this.opts = opts; }

        Object node(Object o, int depth) {
            if (o == null) return null;
            if (isScalar(o)) return scalar(o);
            if (++nodes > opts.maxNodes) return "<node budget exhausted>";

            String prior = seen.get(o);
            if (prior != null) return Map.of("_cycle", prior);

            Class<?> c = o.getClass();
            String cn = c.getName();
            if (opaque(cn)) return "<" + cn + "> " + clip(safeToString(o), 120);

            String h = opts.handles ? Handles.of(o) : "#" + System.identityHashCode(o);
            seen.put(o, h);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_class", cn);
            if (opts.handles) out.put("_h", h);

            if (depth >= opts.maxDepth) {
                out.put("_elided", summary(o));
                return out;
            }

            try {
                if (c.isArray()) return array(o, out, depth);
                if (isByteBuf(c)) return byteBuf(o, out);
                if (o instanceof Collection<?> col) {
                    out.put("_size", col.size());
                    List<Object> items = new ArrayList<>();
                    int i = 0;
                    for (Object e : new ArrayList<>(col)) {
                        if (i++ >= opts.maxElems) { items.add("<" + (col.size() - opts.maxElems) + " more>"); break; }
                        items.add(node(e, depth + 1));
                    }
                    out.put("_items", items);
                    return out;
                }
                if (o instanceof Map<?, ?> m) {
                    out.put("_size", m.size());
                    List<Object> entries = new ArrayList<>();
                    int i = 0;
                    for (Map.Entry<?, ?> e : new ArrayList<>(m.entrySet())) {
                        if (i++ >= opts.maxElems) { entries.add("<" + (m.size() - opts.maxElems) + " more>"); break; }
                        entries.add(List.of(String.valueOf(e.getKey()), node(e.getValue(), depth + 1)));
                    }
                    out.put("_entries", entries);
                    return out;
                }
                // fastutil / other non-JDK collections still expose size(); note it when present
                for (Field f : fieldsOf(c, opts.statics)) {
                    Object v;
                    try { v = f.get(o); } catch (Throwable t) { v = "<inaccessible: " + t.getClass().getSimpleName() + ">"; }
                    out.put(f.getName(), node(v, depth + 1));
                }
            } catch (Throwable t) {
                out.put("_error", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return out;
        }

        private Object array(Object o, Map<String, Object> out, int depth) {
            int len = Array.getLength(o);
            out.put("_size", len);
            Class<?> comp = o.getClass().getComponentType();
            if (comp == byte.class) {
                byte[] b = (byte[]) o;
                out.put("_hex", hex(b, 0, Math.min(len, 128)));
                if (len > 128) out.put("_truncated", true);
                return out;
            }
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                if (i >= opts.maxElems) { items.add("<" + (len - opts.maxElems) + " more>"); break; }
                items.add(node(Array.get(o, i), depth + 1));
            }
            out.put("_items", items);
            return out;
        }

        private Object byteBuf(Object buf, Map<String, Object> out) {
            try {
                Method readable = buf.getClass().getMethod("readableBytes");
                Method readerIndex = buf.getClass().getMethod("readerIndex");
                Method getByte = buf.getClass().getMethod("getByte", int.class);
                int n = (Integer) readable.invoke(buf);
                int ri = (Integer) readerIndex.invoke(buf);
                out.put("_readableBytes", n);
                int take = Math.min(n, 128);
                byte[] b = new byte[take];
                for (int i = 0; i < take; i++) b[i] = (Byte) getByte.invoke(buf, ri + i);
                out.put("_hex", hex(b, 0, take));
                if (n > take) out.put("_truncated", true);
            } catch (Throwable t) {
                out.put("_error", "ByteBuf read failed (likely released): " + t.getClass().getSimpleName());
            }
            return out;
        }

        void strings(Object o, int depth, String path, StringSink sink) {
            if (o == null || depth > opts.maxDepth || ++nodes > opts.maxNodes) return;
            if (o instanceof CharSequence cs) {
                sink.accept(path, cs.toString(), o);
                return;
            }
            if (isScalar(o)) {
                if (o instanceof Enum<?> e) sink.accept(path, e.name(), o);
                return;
            }
            if (seen.containsKey(o)) return;
            Class<?> c = o.getClass();
            if (opaque(c.getName())) return;
            seen.put(o, path);
            try {
                if (c.isArray()) {
                    if (c.getComponentType().isPrimitive()) return;
                    int len = Math.min(Array.getLength(o), opts.maxElems);
                    for (int i = 0; i < len; i++) strings(Array.get(o, i), depth + 1, path + "[" + i + "]", sink);
                    return;
                }
                if (o instanceof Collection<?> col) {
                    int i = 0;
                    for (Object e : new ArrayList<>(col)) {
                        if (i >= opts.maxElems) break;
                        strings(e, depth + 1, path + "[" + i + "]", sink);
                        i++;
                    }
                    return;
                }
                if (o instanceof Map<?, ?> m) {
                    int i = 0;
                    for (Map.Entry<?, ?> e : new ArrayList<>(m.entrySet())) {
                        if (i++ >= opts.maxElems) break;
                        strings(e.getKey(), depth + 1, path + "{k}", sink);
                        strings(e.getValue(), depth + 1, path + "{" + e.getKey() + "}", sink);
                    }
                    return;
                }
                for (Field f : fieldsOf(c, false)) {
                    Object v;
                    try { v = f.get(o); } catch (Throwable t) { continue; }
                    strings(v, depth + 1, path.isEmpty() ? f.getName() : path + "." + f.getName(), sink);
                }
            } catch (Throwable ignored) {
                // partial results are fine
            }
        }
    }

    // ---------------------------------------------------------------

    static List<Field> fieldsOf(Class<?> c, boolean statics) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fs;
            try { fs = k.getDeclaredFields(); } catch (Throwable t) { break; }
            for (Field f : fs) {
                // Synthetic fields are kept on purpose: `this$0` and lambda/anonymous captures like
                // `val$eventExecutor` are often the only reference from a Runnable back to the object
                // graph we're trying to reach. Skipping them hides Netty's event loops entirely.
                if (Modifier.isStatic(f.getModifiers()) != statics) continue;
                try { f.setAccessible(true); } catch (Throwable t) { continue; }
                out.add(f);
            }
        }
        return out;
    }

    static boolean isScalar(Object o) {
        return o instanceof Number || o instanceof Boolean || o instanceof Character
                || o instanceof CharSequence || o instanceof Enum<?>;
    }

    private static Object scalar(Object o) {
        if (o instanceof CharSequence cs) return clip(cs.toString(), 300);
        if (o instanceof Character c) return String.valueOf(c);
        if (o instanceof Enum<?> e) return e.getDeclaringClass().getSimpleName() + "." + e.name();
        if (o instanceof Float f) return f.doubleValue();
        return o;
    }

    private static boolean opaque(String cn) {
        for (String p : OPAQUE_PREFIXES) if (cn.startsWith(p)) return true;
        return false;
    }

    static boolean isByteBuf(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals("io.netty.buffer.ByteBuf")) return true;
            for (Class<?> i : k.getInterfaces()) if (i.getName().equals("io.netty.buffer.ByteBuf")) return true;
        }
        return false;
    }

    private static String safeToString(Object o) {
        try { return String.valueOf(o); } catch (Throwable t) { return "<toString threw " + t.getClass().getSimpleName() + ">"; }
    }

    static String simple(Class<?> c) {
        String n = c.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…(" + s.length() + " chars)";
    }

    static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) sb.append(String.format("%02x", b[off + i]));
        return sb.toString();
    }

    private Introspect() {}
}
