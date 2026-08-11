package dev.mcinject.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ring buffers of observed packets plus the rules that decide what happens to them.
 *
 * <p>Two rings on purpose. Metadata (sequence, direction, class, timestamp) is cheap so we keep a
 * lot of it; the packet objects themselves can be megabytes (chunk data), so only the most recent
 * few hundred are pinned for later inspection or replay. Anything on a watch list gets introspected
 * eagerly into JSON, which both survives the object being recycled and feeds the live event stream.
 */
public final class PacketStore {

    public static final class Rec {
        public final long seq;
        public final long ts;
        public final String dir;      // "in" (server->client) or "out" (client->server)
        public final String type;     // runtime class name
        volatile Object obj;          // pinned only while inside the retain window
        volatile String eagerJson;    // set when a watch rule matched
        volatile boolean blocked;

        Rec(long seq, String dir, String type, Object obj) {
            this.seq = seq;
            this.ts = System.currentTimeMillis();
            this.dir = dir;
            this.type = type;
            this.obj = obj;
        }

        public Map<String, Object> meta() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("ts", ts);
            m.put("dir", dir);
            m.put("type", type);
            Object o = obj;
            m.put("retained", o != null);
            if (o != null) m.put("handle", Handles.of(o));
            if (blocked) m.put("blocked", true);
            return m;
        }
    }

    // ---- configuration knobs, all live-tunable over HTTP ----
    public static volatile boolean recording = true;
    public static volatile int retainCount = 128;
    /** Class-name substrings that are never recorded at all — the way to silence chunk spam. */
    public static final CopyOnWriteArrayList<String> mute = new CopyOnWriteArrayList<>();
    /** Class-name substrings that get eagerly introspected and pushed to the event stream. */
    public static final CopyOnWriteArrayList<String> watch = new CopyOnWriteArrayList<>();
    /** Class-name substrings whose packets are dropped before reaching the game or the server. */
    public static final CopyOnWriteArrayList<String> block = new CopyOnWriteArrayList<>();

    private static final int RING = 8192;
    private static final Rec[] ring = new Rec[RING];
    private static final AtomicLong seqGen = new AtomicLong();
    private static final Map<String, long[]> counts = new ConcurrentHashMap<>(); // type -> [in, out]

    /**
     * Records a packet, transparently expanding bundles.
     *
     * <p>The tap sits below Minecraft's {@code bundler} handler, so anything the server groups
     * between bundle delimiters arrives as a single {@code ClientboundBundlePacket} with its real
     * contents sealed inside — entity updates, and on some servers the interesting ones too. Left
     * alone, that makes a large slice of traffic invisible. Each sub-packet is recorded under its own
     * sequence number so it filters, retains and inspects like any other.
     *
     * <p>Blocking still operates on the wrapper: dropping one packet out of a bundle would mean
     * rebuilding it, and a half-applied bundle is worse than none.
     *
     * @return the record for the message itself, or null if it was muted
     */
    public static Rec record(String dir, Object msg) {
        if (!recording || msg == null) return null;

        Iterable<?> subPackets = bundleContents(msg);
        if (subPackets != null) {
            for (Object sub : subPackets) {
                try {
                    record(dir, sub);
                } catch (Throwable ignored) {
                    // one bad sub-packet must not cost us the rest of the bundle
                }
            }
        }

        String type = msg.getClass().getName();
        if (matches(mute, type)) return null;

        counts.computeIfAbsent(type, k -> new long[2])["in".equals(dir) ? 0 : 1]++;

        long s = seqGen.incrementAndGet();
        Rec r = new Rec(s, dir, type, msg);
        int idx = (int) (s % RING);
        synchronized (ring) {
            ring[idx] = r;
            long clearAt = s - retainCount;
            if (clearAt > 0) {
                Rec old = ring[(int) (clearAt % RING)];
                if (old != null && old.seq == clearAt) old.obj = null;
            }
        }

        if (matches(watch, type)) {
            try {
                Introspect.Opts o = new Introspect.Opts();
                o.maxDepth = 8;
                o.maxNodes = 600;
                r.eagerJson = Json.write(Introspect.tree(msg, o));
            } catch (Throwable t) {
                r.eagerJson = Json.write(Json.map("_error", String.valueOf(t)));
            }
            Events.push("packet", Json.map("seq", String.valueOf(s), "dir", dir, "type", type), r.eagerJson);
        }

        if (matches(block, type)) {
            r.blocked = true;
            return r;
        }
        return r;
    }

    public static boolean shouldBlock(String type) {
        return matches(block, type);
    }

    private static final Map<Class<?>, Optional<java.lang.reflect.Method>> BUNDLE_ACCESSOR = new ConcurrentHashMap<>();

    /**
     * Duck-typed bundle detection: any no-argument {@code subPackets()} returning an Iterable. Going
     * by shape rather than by the {@code BundlePacket} class name keeps this working if the type is
     * renamed or relocated between game versions.
     */
    private static Iterable<?> bundleContents(Object msg) {
        var accessor = BUNDLE_ACCESSOR.computeIfAbsent(msg.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (var m : k.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getName().equals("subPackets")
                            && Iterable.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        return Optional.of(m);
                    }
                }
            }
            return Optional.empty();
        });
        if (accessor.isEmpty()) return null;
        try {
            return (Iterable<?>) accessor.get().invoke(msg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean matches(List<String> rules, String type) {
        if (rules.isEmpty()) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        for (String r : rules) {
            if (r.isEmpty()) continue;
            if (lower.contains(r.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static Rec get(long seq) {
        if (seq <= 0) return null;
        synchronized (ring) {
            Rec r = ring[(int) (seq % RING)];
            return r != null && r.seq == seq ? r : null;
        }
    }

    /** Newest-first listing with optional direction / substring filters. */
    public static List<Rec> list(int limit, String dir, String typeContains, long sinceSeq) {
        List<Rec> out = new ArrayList<>();
        long top = seqGen.get();
        synchronized (ring) {
            for (long s = top; s > 0 && s > top - RING && out.size() < limit; s--) {
                if (s <= sinceSeq) break;
                Rec r = ring[(int) (s % RING)];
                if (r == null || r.seq != s) continue;
                if (dir != null && !dir.isEmpty() && !dir.equals(r.dir)) continue;
                if (typeContains != null && !typeContains.isEmpty()
                        && !r.type.toLowerCase(Locale.ROOT).contains(typeContains.toLowerCase(Locale.ROOT))) continue;
                out.add(r);
            }
        }
        return out;
    }

    public static Map<String, Object> stats() {
        Map<String, Object> byType = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]))
                .forEach(e -> byType.put(e.getKey(), Json.map(
                        "in", String.valueOf(e.getValue()[0]),
                        "out", String.valueOf(e.getValue()[1]))));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", seqGen.get());
        m.put("recording", recording);
        m.put("retainCount", retainCount);
        m.put("distinctTypes", counts.size());
        m.put("mute", new ArrayList<>(mute));
        m.put("watch", new ArrayList<>(watch));
        m.put("block", new ArrayList<>(block));
        m.put("byType", byType);
        return m;
    }

    /**
     * Out-of-the-box filtering. Movement and terrain packets are 90%+ of the traffic and are almost
     * never what an assistant is looking for; with them muted the retain window covers minutes of
     * meaningful events instead of a couple of seconds.
     */
    public static void applyDefaults() {
        mute.addAllAbsent(List.of(
                "MoveEntity", "RotateHead", "ClientTickEnd", "MovePlayer", "SetTime", "KeepAlive",
                "SetEntityMotion", "TeleportEntity", "EntityPositionSync", "SectionBlocksUpdate",
                "LightUpdate", "LevelChunk", "SetEntityData", "EntityEvent", "SoundPacket",
                "Particle", "BlockUpdate", "BlockEvent", "SetPassengers", "UpdateAttributes",
                "PlayerInput", "SwingPacket", "SetChunkCacheCenter", "ChunkBatch"));
        watch.addAllAbsent(List.of(
                "SystemChat", "PlayerChat", "ContainerClick", "OpenScreen", "ContainerSetContent",
                "ContainerClose", "Disconnect", "Transfer", "Respawn", "Login"));
    }

    public static Map<String, Object> configSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recording", recording);
        m.put("retainCount", retainCount);
        m.put("mute", new ArrayList<>(mute));
        m.put("watch", new ArrayList<>(watch));
        m.put("block", new ArrayList<>(block));
        return m;
    }

    public static void applyConfig(Map<String, ?> cfg) {
        if (cfg.containsKey("recording")) recording = Json.bool(cfg, "recording", true);
        if (cfg.containsKey("retainCount")) retainCount = Math.max(0, Json.i(cfg, "retainCount", 128));
        replace(cfg, "mute", mute);
        replace(cfg, "watch", watch);
        replace(cfg, "block", block);
    }

    private static void replace(Map<String, ?> cfg, String key, CopyOnWriteArrayList<String> target) {
        if (!cfg.containsKey(key)) return;
        List<String> next = new ArrayList<>();
        for (Object o : Json.list(cfg, key)) next.add(String.valueOf(o));
        target.clear();
        target.addAll(next);
    }

    public static void reset() {
        synchronized (ring) {
            java.util.Arrays.fill(ring, null);
        }
        counts.clear();
    }

    private PacketStore() {}
}
