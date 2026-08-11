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
import java.util.Map;

/**
 * Locates the live {@code io.netty.channel.Channel} that Minecraft is using to talk to the server,
 * without any bytecode instrumentation and without knowing a single obfuscated Minecraft name.
 *
 * <p>The trick: Netty itself is never obfuscated (it's a plain library jar), and Minecraft's event
 * loop threads are Netty threads. We reflectively walk out from those threads' task objects until we
 * bump into Channel instances. That works across Netty 4.1's {@code NioEventLoop} and 4.2's
 * {@code SingleThreadIoEventLoop}/{@code NioIoHandler} split, because we never name a field.
 */
public final class Discovery {

    private static final String CHANNEL_IFACE = "io.netty.channel.Channel";

    /** Don't walk into these — they either explode combinatorially or lead back to the whole VM. */
    private static final String[] SKIP_PREFIXES = {
            "java.lang.Thread", "java.lang.ThreadGroup", "java.lang.Class", "java.lang.ClassLoader",
            "java.lang.Module", "java.lang.reflect.", "java.lang.invoke.", "java.security.",
            "jdk.internal.", "java.util.logging.", "org.apache.logging.",
    };

    // ------------------------------------------------------------------

    public static List<Object> findChannels() {
        IdentityHashMap<Object, Boolean> found = new IdentityHashMap<>();
        for (Thread t : nettyThreads()) {
            Object task = threadTask(t);
            if (task != null) walkForChannels(task, found);
            Object tlm = fieldValue(t, "threadLocalMap"); // FastThreadLocalThread
            if (tlm != null) walkForChannels(tlm, found);
        }
        List<Object> out = new ArrayList<>(found.keySet());
        out.sort((a, b) -> Integer.compare(System.identityHashCode(a), System.identityHashCode(b)));
        return out;
    }

    /** Channels that look like a Minecraft game connection: active, and carrying a packet_handler. */
    public static List<Object> findGameChannels() {
        List<Object> out = new ArrayList<>();
        for (Object ch : findChannels()) {
            try {
                if (!(Boolean) call(ch, "isActive") ) continue;
                if (pipelineNames(ch).contains("packet_handler")) out.add(ch);
            } catch (Throwable ignored) {
                // a channel we can't inspect is a channel we don't want
            }
        }
        return out;
    }

    public static List<Thread> nettyThreads() {
        List<Thread> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName() == null ? "" : t.getName().toLowerCase();
            boolean byName = name.contains("netty") || name.contains("epoll") || name.contains("kqueue")
                    || name.contains("nioeventloop");
            boolean byClass = t.getClass().getName().startsWith("io.netty");
            Object task = byName || byClass ? null : threadTask(t);
            boolean byTask = task != null && task.getClass().getName().startsWith("io.netty");
            if (byName || byClass || byTask) out.add(t);
        }
        return out;
    }

    private static Object threadTask(Thread t) {
        Object v = fieldValue(t, "target");           // JDK <= 18
        if (v != null) return v;
        Object holder = fieldValue(t, "holder");      // JDK 19+ moved it into Thread.FieldHolder
        return holder == null ? null : fieldValue(holder, "task");
    }

    // ------------------------------------------------------------------

    private static void walkForChannels(Object root, IdentityHashMap<Object, Boolean> found) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object[]> queue = new ArrayDeque<>();       // [object, depth]
        queue.add(new Object[]{root, 0});
        int budget = 30_000;

        while (!queue.isEmpty() && budget-- > 0) {
            Object[] cur = queue.poll();
            Object o = cur[0];
            int depth = (Integer) cur[1];
            if (o == null || depth > 10 || seen.put(o, Boolean.TRUE) != null) continue;

            Class<?> c = o.getClass();
            if (Introspect.isScalar(o)) continue;
            if (skip(c.getName())) continue;

            if (isChannel(c)) {
                found.put(o, Boolean.TRUE);
                continue; // never descend into a channel: that's the whole game object graph
            }

            try {
                if (c.isArray()) {
                    if (c.getComponentType().isPrimitive()) continue;
                    int len = Math.min(Array.getLength(o), 4096);
                    for (int i = 0; i < len; i++) queue.add(new Object[]{Array.get(o, i), depth + 1});
                    continue;
                }
                if (o instanceof Collection<?> col) {
                    for (Object e : new ArrayList<>(col)) queue.add(new Object[]{e, depth + 1});
                    continue;
                }
                if (o instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : new ArrayList<>(m.entrySet())) {
                        queue.add(new Object[]{e.getKey(), depth + 1});
                        queue.add(new Object[]{e.getValue(), depth + 1});
                    }
                    continue;
                }
                for (Field f : Introspect.fieldsOf(c, false)) {
                    if (f.getType().isPrimitive()) continue;
                    Object v;
                    try { v = f.get(o); } catch (Throwable t) { continue; }
                    queue.add(new Object[]{v, depth + 1});
                }
            } catch (Throwable ignored) {
                // concurrent mutation by the game is expected; partial results are fine
            }
        }
    }

    private static boolean skip(String cn) {
        for (String p : SKIP_PREFIXES) if (cn.startsWith(p)) return true;
        return false;
    }

    public static boolean isChannel(Class<?> c) {
        return implementsInterface(c, CHANNEL_IFACE);
    }

    static boolean implementsInterface(Class<?> c, String iface) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals(iface)) return true;
            if (hasIface(k, iface)) return true;
        }
        return false;
    }

    private static boolean hasIface(Class<?> k, String iface) {
        for (Class<?> i : k.getInterfaces()) {
            if (i.getName().equals(iface) || hasIface(i, iface)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static List<String> pipelineNames(Object channel) throws Exception {
        Object pipeline = call(channel, "pipeline");
        return new ArrayList<>((List<String>) call(pipeline, "names"));
    }

    public static Map<String, Object> describeChannel(Object channel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handle", Handles.of(channel));
        m.put("class", channel.getClass().getName());
        try { m.put("active", call(channel, "isActive")); } catch (Throwable t) { m.put("active", "?"); }
        try { m.put("open", call(channel, "isOpen")); } catch (Throwable t) { m.put("open", "?"); }
        try { m.put("remote", String.valueOf(call(channel, "remoteAddress"))); } catch (Throwable ignored) { }
        try { m.put("local", String.valueOf(call(channel, "localAddress"))); } catch (Throwable ignored) { }
        try {
            List<String> names = pipelineNames(channel);
            m.put("pipeline", names);
            m.put("isGameConnection", names.contains("packet_handler"));
            Map<String, Object> handlers = new LinkedHashMap<>();
            Object pipeline = call(channel, "pipeline");
            for (String n : names) {
                try {
                    Object h = pipeline.getClass().getMethod("get", String.class).invoke(pipeline, n);
                    handlers.put(n, h == null ? null : Json.map("class", h.getClass().getName(), "handle", Handles.of(h)));
                } catch (Throwable t) {
                    handlers.put(n, "<error>");
                }
            }
            m.put("handlers", handlers);
        } catch (Throwable t) {
            m.put("pipelineError", String.valueOf(t));
        }
        return m;
    }

    public static Object call(Object target, String method, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = args[i] == null ? Object.class : args[i].getClass();
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                var m = k.getMethod(method, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                // try the declared-methods route below
            }
        }
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            for (var m : k.getDeclaredMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + method + "/" + args.length);
    }

    public static Object fieldValue(Object o, String name) {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private Discovery() {}
}
