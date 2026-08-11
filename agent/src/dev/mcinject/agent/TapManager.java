package dev.mcinject.agent;

import java.lang.invoke.MethodHandles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Splices {@code io.netty.channel.McInjectTap} into a live channel pipeline and takes it back out
 * again, with no game restart and no bytecode rewriting.
 *
 * <p>The awkward part is classloaders. Under Fabric, Netty lives in KnotClassLoader while the agent
 * lives in the system loader, so the agent cannot even name {@code ChannelDuplexHandler}. We solve
 * it by defining the tap's bytes directly into Netty's own package via a private
 * {@link MethodHandles.Lookup}, and by talking to it only through {@link Function}, which both
 * loaders resolve to the same {@code java.base} interface.
 */
public final class TapManager {

    public static final String TAP_CLASS = "io.netty.channel.McInjectTap";
    public static final String TAP_NAME = "mcinject_tap";

    private static final Map<ClassLoader, Class<?>> tapClasses = new ConcurrentHashMap<>();
    /** channel handle -> its ChannelHandlerContext, needed to inject inbound packets. */
    private static final Map<String, Object> contexts = new ConcurrentHashMap<>();
    private static final Map<String, Object> installed = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------

    public static synchronized Map<String, Object> install(Object channel) throws Exception {
        ClassLoader loader = channel.getClass().getClassLoader();
        Class<?> tapClass = tapClass(loader);

        Object pipeline = Discovery.call(channel, "pipeline");
        List<String> names = Discovery.pipelineNames(channel);
        if (names.contains(TAP_NAME)) {
            return Json.map("status", "already-installed", "channel", Handles.of(channel));
        }
        if (!names.contains("packet_handler")) {
            throw new IllegalStateException("channel has no 'packet_handler' — not a Minecraft game connection: " + names);
        }

        Class<?> handlerIface = loader.loadClass("io.netty.channel.ChannelHandler");
        Object tap = tapClass.getConstructor(Function.class).newInstance(callback());

        var addBefore = pipeline.getClass().getMethod("addBefore", String.class, String.class, handlerIface);
        addBefore.setAccessible(true);

        onEventLoop(channel, () -> addBefore.invoke(pipeline, "packet_handler", TAP_NAME, tap));

        installed.put(Handles.of(channel), channel);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "installed");
        res.put("channel", Handles.of(channel));
        res.put("pipeline", Discovery.pipelineNames(channel));
        Log.info("tap installed on channel " + Handles.of(channel));
        Events.push("tap", Json.map("state", "installed", "channel", Handles.of(channel)));
        return res;
    }

    public static synchronized Map<String, Object> remove(Object channel) throws Exception {
        Object pipeline = Discovery.call(channel, "pipeline");
        List<String> names = Discovery.pipelineNames(channel);
        if (!names.contains(TAP_NAME)) return Json.map("status", "not-installed");
        var remove = pipeline.getClass().getMethod("remove", String.class);
        onEventLoop(channel, () -> remove.invoke(pipeline, TAP_NAME));
        installed.remove(Handles.of(channel));
        contexts.remove(Handles.of(channel));
        Log.info("tap removed from channel " + Handles.of(channel));
        Events.push("tap", Json.map("state", "removed", "channel", Handles.of(channel)));
        return Json.map("status", "removed");
    }

    public static void removeAll() {
        for (Object ch : List.copyOf(installed.values())) {
            try { remove(ch); } catch (Throwable t) { Log.error("tap removal failed", t); }
        }
    }

    public static Map<String, Object> installedChannels() {
        pruneDead();
        Map<String, Object> m = new LinkedHashMap<>();
        installed.forEach((h, ch) -> m.put(h, Discovery.describeChannel(ch)));
        return m;
    }

    /**
     * Best guess at "the" connection. Always prefers a live channel: after a server transfer the old
     * one lingers in the map until its close event lands, and answering with it would send the
     * player's packets into a dead socket.
     */
    public static Object primaryChannel() {
        pruneDead();
        for (Object ch : installed.values()) if (isLive(ch)) return ch;
        for (Object ch : Discovery.findGameChannels()) if (isLive(ch)) return ch;
        if (!installed.isEmpty()) return installed.values().iterator().next();
        List<Object> game = Discovery.findGameChannels();
        return game.isEmpty() ? null : game.get(0);
    }

    private static boolean isLive(Object channel) {
        try {
            return Boolean.TRUE.equals(Discovery.call(channel, "isActive"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void pruneDead() {
        for (var e : List.copyOf(installed.entrySet())) {
            if (!isLive(e.getValue())) {
                installed.remove(e.getKey());
                contexts.remove(e.getKey());
            }
        }
    }

    // ------------------------------------------------------------------

    /** Sends a packet object to the server, exactly as if the client had produced it. */
    public static void sendOutbound(Object channel, Object packet) throws Exception {
        onEventLoop(channel, () -> Discovery.call(channel, "writeAndFlush", packet));
    }

    /**
     * Feeds a packet to the client as if the server had sent it. It enters the pipeline just below
     * the tap, so {@code packet_handler} — and therefore the whole game — treats it as genuine.
     */
    public static void sendInbound(Object channel, Object packet) throws Exception {
        Object ctx = contexts.get(Handles.of(channel));
        if (ctx == null) {
            Object pipeline = Discovery.call(channel, "pipeline");
            ctx = pipeline.getClass().getMethod("context", String.class).invoke(pipeline, TAP_NAME);
        }
        if (ctx == null) throw new IllegalStateException("tap is not installed on this channel");
        Object c = ctx;
        onEventLoop(channel, () -> Discovery.call(c, "fireChannelRead", packet));
    }

    // ------------------------------------------------------------------

    private static Function<Object[], Object> callback() {
        return args -> {
            String tag = (String) args[0];
            Object msg = args[1];
            Object ctx = args[2];
            try {
                switch (tag) {
                    case "in", "out" -> {
                        PacketStore.Rec r = PacketStore.record(tag, msg);
                        if (r != null && r.blocked) return null;
                        return msg;
                    }
                    case "added" -> {
                        Object ch = Discovery.call(ctx, "channel");
                        contexts.put(Handles.of(ch), ctx);
                        return null;
                    }
                    case "removed", "inactive", "exception" -> {
                        // Servers like hub networks transfer players by dropping the connection and
                        // opening a new one, so channels die routinely. Forget dead ones here or
                        // primaryChannel() starts handing out corpses after the first transfer.
                        String h = null;
                        try {
                            Object ch = Discovery.call(ctx, "channel");
                            h = Handles.of(ch);
                            if (!"removed".equals(tag)) {
                                installed.remove(h);
                                contexts.remove(h);
                            }
                        } catch (Throwable ignored) { }
                        Events.push("channel", Json.map("state", tag, "channel", h));
                        return null;
                    }
                    default -> {
                        return msg;
                    }
                }
            } catch (Throwable t) {
                return msg; // never break the pipeline
            }
        };
    }

    private static Class<?> tapClass(ClassLoader loader) throws Exception {
        Class<?> cached = tapClasses.get(loader);
        if (cached != null) return cached;
        try {
            Class<?> existing = loader.loadClass(TAP_CLASS);
            tapClasses.put(loader, existing);
            return existing;
        } catch (ClassNotFoundException expected) {
            // first install on this loader: define it below
        }

        byte[] bytes = tapBytecode();

        Class<?> anchor = loader.loadClass("io.netty.channel.ChannelDuplexHandler");
        Class<?> defined;
        try {
            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(anchor, MethodHandles.lookup());
            defined = lk.defineClass(bytes);
        } catch (Throwable primaryFailure) {
            Log.warn("privateLookupIn define failed (" + primaryFailure + "), falling back to ClassLoader.defineClass");
            var m = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            m.setAccessible(true);
            defined = (Class<?>) m.invoke(loader, TAP_CLASS, bytes, 0, bytes.length);
        }
        tapClasses.put(loader, defined);
        Log.info("defined " + TAP_CLASS + " into " + loader);
        return defined;
    }

    /**
     * Reads the tap's compiled bytes out of our own jar.
     *
     * <p>Deliberately avoids {@code getResourceAsStream}: previous generations of the agent are still
     * indexed by the system classloader, and a parent-first lookup returns a URL into a jar that has
     * since been rebuilt — which fails to open and silently yields null. Going through
     * {@link java.net.URLClassLoader#findResource} searches only our own jar, and the code-source
     * fallback covers the case where we weren't loaded from one.
     */
    private static byte[] tapBytecode() throws Exception {
        String entry = "mcinject/McInjectTap.class";
        ClassLoader cl = TapManager.class.getClassLoader();
        if (cl instanceof java.net.URLClassLoader u) {
            java.net.URL url = u.findResource(entry);
            if (url != null) {
                try (var in = url.openStream()) {
                    return in.readAllBytes();
                }
            }
        }
        try (var in = TapManager.class.getResourceAsStream("/" + entry)) {
            if (in != null) return in.readAllBytes();
        } catch (Throwable ignored) {
            // fall through to the code-source route
        }
        var src = TapManager.class.getProtectionDomain().getCodeSource();
        if (src != null) {
            try (var jar = new java.util.jar.JarFile(new java.io.File(src.getLocation().toURI()))) {
                var e = jar.getJarEntry(entry);
                if (e != null) {
                    try (var in = jar.getInputStream(e)) {
                        return in.readAllBytes();
                    }
                }
            }
        }
        throw new IllegalStateException("tap bytecode (" + entry + ") not found in the agent core jar");
    }

    /**
     * Runs work on the channel's event loop. Mutating a pipeline or writing from an HTTP thread is
     * how you corrupt a connection; Netty only promises safety on its own thread.
     */
    static void onEventLoop(Object channel, ThrowingRunnable work) throws Exception {
        Object loop = Discovery.call(channel, "eventLoop");
        if (!(loop instanceof Executor exec)) throw new IllegalStateException("event loop is not an Executor");
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        exec.execute(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("event loop did not run the task within 5s");
        Throwable t = err.get();
        if (t != null) {
            if (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null) t = ite.getCause();
            throw t instanceof Exception e ? e : new RuntimeException(t);
        }
    }

    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private TapManager() {}
}
