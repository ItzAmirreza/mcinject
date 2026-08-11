package dev.mcinject.agent;

import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent entry point. Loaded into a <em>running</em> Minecraft through the JVM attach mechanism, so
 * the player never restarts the game and never drops their connection.
 *
 * <p>What it sets up, in order: open {@code java.base} to ourselves so reflection isn't fenced off,
 * start the local control API, find the live Netty channel, and splice the packet tap into it.
 */
public final class AgentMain {

    public static final String VERSION = "0.1.0";

    private static volatile Instrumentation inst;
    private static volatile HttpApi api;
    private static volatile long startedAt;
    private static volatile String token;
    private static volatile int port;
    private static volatile boolean autoTapRunning;
    private static volatile Path home;

    public static void premain(String args, Instrumentation i) { start(args, i); }

    public static void agentmain(String args, Instrumentation i) { start(args, i); }

    private static synchronized void start(String args, Instrumentation i) {
        if (api != null) {
            Log.info("agent already running on port " + port + "; ignoring duplicate attach");
            return;
        }
        inst = i;
        autoTapRunning = true;
        startedAt = System.currentTimeMillis();
        Map<String, String> opts = parseArgs(args);
        Path homeDir = Path.of(opts.getOrDefault("home", System.getProperty("user.home") + "/.mcinject"));
        Log.init(homeDir);
        Log.info("mcinject agent " + VERSION + " attaching to pid " + ProcessHandle.current().pid());

        try {
            openAllModules(i);
        } catch (Throwable t) {
            Log.error("could not open modules (deep reflection may be limited)", t);
        }

        home = homeDir;
        loadConfig();

        try {
            port = Integer.parseInt(opts.getOrDefault("port", "0"));
            token = opts.getOrDefault("token", newToken());
            api = new HttpApi(port, token);
            api.start();
            port = api.port();
            writeSession(homeDir, port, token);
            Log.info("control API listening on http://127.0.0.1:" + port);
        } catch (Throwable t) {
            Log.error("failed to start control API", t);
            return;
        }

        if (!"false".equals(opts.get("autotap"))) {
            new Thread(AgentMain::autoTap, "mcinject-autotap").start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { TapManager.removeAll(); } catch (Throwable ignored) { }
        }, "mcinject-shutdown"));
    }

    /**
     * The game may be on the title screen when we attach, so poll for a while rather than giving up:
     * the moment the player joins a server, the tap goes in.
     */
    private static void autoTap() {
        while (autoTapRunning) {
            try {
                for (Object ch : Discovery.findGameChannels()) {
                    if (!Discovery.pipelineNames(ch).contains(TapManager.TAP_NAME)) {
                        TapManager.install(ch);
                    }
                }
            } catch (Throwable t) {
                Log.warn("autotap attempt failed: " + t);
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    /** Called by the bootstrap before a newer generation takes over. */
    public static synchronized void shutdownForReload() {
        Log.info("shutting down for reload");
        autoTapRunning = false;
        try { TapManager.removeAll(); } catch (Throwable t) { Log.error("tap removal failed", t); }
        try { if (api != null) api.stop(); } catch (Throwable t) { Log.error("API stop failed", t); }
        api = null;
        Handles.clear();
    }

    // ------------------------------------------------------------------

    /**
     * Grants our unnamed module deep-reflection access to every package of every boot-layer module.
     * Only an agent can do this. Without it, {@code setAccessible} fails on JDK internals we have to
     * walk through — thread internals on the way to Netty's channels, {@code sun.instrument} when
     * calling back into instrumentation — and the failures surface as confusing dead ends much later.
     */
    private static void openAllModules(Instrumentation i) {
        Module us = AgentMain.class.getModule();
        Set<Module> toUs = Set.of(us);
        int opened = 0, modules = 0;
        for (Module m : ModuleLayer.boot().modules()) {
            if (!m.isNamed()) continue;
            Map<String, Set<Module>> opens = new HashMap<>();
            for (String p : m.getPackages()) opens.put(p, toUs);
            if (opens.isEmpty()) continue;
            try {
                i.redefineModule(m, Set.of(), Map.of(), opens, Set.of(), Map.of());
                opened += opens.size();
                modules++;
            } catch (Throwable t) {
                Log.warn("could not open module " + m.getName() + ": " + t);
            }
        }
        Log.info("opened " + opened + " packages across " + modules + " modules for deep reflection");
    }

    public static Instrumentation instrumentation() { return inst; }

    public static Class<?>[] loadedClasses() {
        Instrumentation i = inst;
        return i == null ? null : i.getAllLoadedClasses();
    }

    /** Every classloader worth trying when resolving a class name by hand. */
    public static List<ClassLoader> candidateLoaders() {
        Set<ClassLoader> out = new LinkedHashSet<>();
        Object primary = TapManager.primaryChannel();
        if (primary != null) out.add(primary.getClass().getClassLoader());
        for (Thread t : Discovery.nettyThreads()) {
            try {
                ClassLoader cl = t.getContextClassLoader();
                if (cl != null) out.add(cl);
            } catch (Throwable ignored) { }
        }
        out.add(AgentMain.class.getClassLoader());
        out.add(ClassLoader.getSystemClassLoader());
        return new ArrayList<>(out);
    }

    public static Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        m.put("pid", ProcessHandle.current().pid());
        m.put("uptimeMs", System.currentTimeMillis() - startedAt);
        m.put("port", port);
        m.put("java", System.getProperty("java.version"));
        m.put("handles", Handles.size());
        m.put("eventHead", Events.head());
        List<Object> chans = new ArrayList<>();
        try {
            for (Object ch : Discovery.findChannels()) chans.add(Discovery.describeChannel(ch));
        } catch (Throwable t) {
            m.put("channelError", String.valueOf(t));
        }
        m.put("channels", chans);
        m.put("tapped", TapManager.installedChannels().keySet());
        m.put("packets", PacketStore.stats().get("total"));
        return m;
    }

    // ------------------------------------------------------------------

    /**
     * Capture rules live in {@code ~/.mcinject/config.json} so they survive reloads and game
     * restarts. Without this, every re-attach drops you back into the raw movement-packet firehose.
     */
    private static void loadConfig() {
        Path f = home.resolve("config.json");
        try {
            if (Files.isRegularFile(f)) {
                PacketStore.applyConfig(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8)));
                Log.info("loaded capture config from " + f);
                return;
            }
        } catch (Throwable t) {
            Log.error("could not read " + f + "; falling back to defaults", t);
        }
        PacketStore.applyDefaults();
        saveConfig();
        Log.info("applied default capture rules");
    }

    public static void saveConfig() {
        if (home == null) return;
        try {
            Files.createDirectories(home);
            Files.writeString(home.resolve("config.json"),
                    Json.write(PacketStore.configSnapshot()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.error("could not save capture config", t);
        }
    }

    private static Map<String, String> parseArgs(String args) {
        Map<String, String> m = new LinkedHashMap<>();
        if (args == null || args.isBlank()) return m;
        for (String part : args.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) m.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return m;
    }

    private static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** The CLI and MCP server read this file to find the agent; 0600 so other users can't. */
    private static void writeSession(Path home, int port, String token) {
        try {
            Files.createDirectories(home);
            Path f = home.resolve("session.json");
            String json = Json.write(Json.map(
                    "port", String.valueOf(port),
                    "token", token,
                    "pid", String.valueOf(ProcessHandle.current().pid()),
                    "version", VERSION));
            Files.writeString(f, json, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Throwable ignored) { }
        } catch (Throwable t) {
            Log.error("could not write session file", t);
        }
    }

    private AgentMain() {}
}
