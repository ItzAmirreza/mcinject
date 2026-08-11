package dev.mcinject.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The control plane: a small JSON/HTTP API bound to loopback, plus an SSE stream for live packets.
 *
 * <p>Deliberately dependency-free ({@code com.sun.net.httpserver} ships with the JDK) because
 * anything else would mean injecting jars into a running game. Every request needs the token written
 * to {@code ~/.mcinject/session.json} — this API can call arbitrary methods inside the game process,
 * so it is not something to leave open.
 */
public final class HttpApi {

    private final HttpServer server;
    private final String token;
    private final Map<String, Route> routes = new LinkedHashMap<>();

    public interface Route {
        Object handle(Req req) throws Exception;
    }

    public HttpApi(int port, String token) throws IOException {
        this.token = token;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32);
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "mcinject-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        server.setExecutor(Executors.newFixedThreadPool(8, tf));
        register();
        server.createContext("/", this::dispatch);
    }

    public void start() { server.start(); }

    public void stop() { server.stop(0); }

    public int port() { return server.getAddress().getPort(); }

    // ------------------------------------------------------------------ routes

    private void register() {
        routes.put("GET /health", r -> Json.map("ok", "true", "version", AgentMain.VERSION));
        routes.put("GET /status", r -> AgentMain.status());
        routes.put("GET /log", r -> Json.map("lines", String.join("\n", Log.tail(r.qi("n", 100)))));

        // ---- connection & tap ----
        routes.put("GET /channels", r -> {
            List<Object> out = new ArrayList<>();
            for (Object ch : Discovery.findChannels()) out.add(Discovery.describeChannel(ch));
            return Json.map2("channels", out, "tapped", TapManager.installedChannels());
        });
        routes.put("POST /tap/install", r -> {
            Object ch = r.channel();
            if (ch == null) throw new IllegalStateException("no game connection found — is the player on a server?");
            return TapManager.install(ch);
        });
        routes.put("POST /tap/remove", r -> {
            Object ch = r.channel();
            if (ch == null) throw new IllegalStateException("no channel specified and none tapped");
            return TapManager.remove(ch);
        });

        // ---- packet observation ----
        routes.put("GET /packets", r -> {
            List<Object> out = new ArrayList<>();
            for (PacketStore.Rec rec : PacketStore.list(r.qi("limit", 50), r.q("dir"), r.q("type"), r.ql("since", 0)))
                out.add(rec.meta());
            return Json.map2("packets", out, "head", PacketStore.stats().get("total"));
        });
        routes.put("GET /packets/stats", r -> PacketStore.stats());
        routes.put("POST /packets/reset", r -> { PacketStore.reset(); return Json.map("ok", "true"); });
        routes.put("POST /packets/config", r -> {
            PacketStore.applyConfig(r.body());
            AgentMain.saveConfig();
            return PacketStore.stats();
        });
        routes.put("GET /packet", r -> {
            long seq = r.ql("seq", 0);
            PacketStore.Rec rec = PacketStore.get(seq);
            if (rec == null) throw new IllegalArgumentException("no packet with seq " + seq + " (it may have aged out)");
            Map<String, Object> out = new LinkedHashMap<>(rec.meta());
            if (rec.eagerJson != null) out.put("data", new Json.Raw(rec.eagerJson));
            else if (rec.obj != null) out.put("data", Introspect.tree(rec.obj, Introspect.Opts.of(r.query())));
            else out.put("data", null);
            return out;
        });

        // ---- events ----
        routes.put("GET /events", r -> {
            long since = r.ql("since", 0);
            int wait = r.qi("wait", 0);
            List<Events.Event> evs = wait > 0 ? Events.await(since, Math.min(wait, 60_000)) : Events.since(since);
            List<Object> out = new ArrayList<>();
            for (Events.Event e : evs) out.add(new Json.Raw(e.json));
            return Json.map2("events", out, "head", Events.head());
        });

        // ---- reflection ----
        routes.put("POST /inspect", r -> {
            Object target = Refl.value(r.body().get("target"));
            return Json.map2("class", target == null ? null : target.getClass().getName(),
                    "value", Introspect.tree(target, Introspect.Opts.of(r.body())));
        });
        routes.put("POST /get", r -> {
            Map<String, Object> b = r.body();
            Object v = Refl.getField(Refl.value(b.get("target")), Json.str(b, "name", ""));
            return result(v, b);
        });
        routes.put("POST /set", r -> {
            Map<String, Object> b = r.body();
            Refl.setField(Refl.value(b.get("target")), Json.str(b, "name", ""), b.get("value"));
            return Json.map("ok", "true");
        });
        routes.put("POST /call", r -> {
            Map<String, Object> b = r.body();
            Object target = Refl.value(b.get("target"));
            Object v = onGameThreadIfAsked(b, () -> Refl.invoke(target, Json.str(b, "method", ""),
                    Json.list(b, "args"), typeList(b)));
            return result(v, b);
        });
        routes.put("POST /new", r -> {
            Map<String, Object> b = r.body();
            Object v = Refl.construct(Json.str(b, "class", ""), Json.list(b, "args"), typeList(b));
            return result(v, b);
        });
        routes.put("POST /clone", r -> {
            Map<String, Object> b = r.body();
            Object src = Refl.value(b.get("target"));
            @SuppressWarnings("unchecked")
            Map<String, Object> ov = b.get("overrides") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            Object v = Copy.cloneWith(src, ov);
            return result(v, b);
        });
        // Reading 46 inventory slots as 46 requests is 46 round-trips an assistant pays for in
        // latency and tokens. One batch does the same work in a single call, and a failing op
        // reports inline instead of aborting the rest.
        routes.put("POST /batch", r -> {
            List<Object> results = new ArrayList<>();
            for (Object o : Json.list(r.body(), "ops")) {
                if (!(o instanceof Map<?, ?> raw)) {
                    results.add(Json.map("error", "each op must be an object"));
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> b = (Map<String, Object>) raw;
                try {
                    results.add(runOp(b));
                } catch (Throwable t) {
                    Throwable c = t instanceof java.lang.reflect.InvocationTargetException ite
                            && ite.getCause() != null ? ite.getCause() : t;
                    results.add(Json.map("error", c.getClass().getSimpleName() + ": " + c.getMessage()));
                }
            }
            return Json.map2("results", results);
        });

        routes.put("POST /search", r -> {
            Map<String, Object> b = r.body();
            List<Object> roots = new ArrayList<>();
            if (b.get("roots") instanceof List<?> l && !l.isEmpty()) {
                for (Object spec : l) roots.add(Refl.value(spec));
            } else if (b.containsKey("staticsPrefix")) {
                roots.addAll(HeapSearch.staticRoots(Json.str(b, "staticsPrefix", "net.minecraft"),
                        Json.i(b, "maxClasses", 4000)));
            } else {
                roots.addAll(HeapSearch.defaultRoots());
            }
            if (roots.isEmpty()) throw new IllegalStateException("no search roots — is the player connected to a server?");
            return HeapSearch.search(roots, HeapSearch.Query.of(b));
        });
        routes.put("GET /classes", r -> Json.map2("classes", Refl.listClasses(r.q("contains"), r.qi("limit", 200))));
        routes.put("GET /class", r -> Refl.describeClass(r.q("name")));

        // ---- writing packets ----
        routes.put("POST /send", r -> {
            Map<String, Object> b = r.body();
            Object ch = r.channel();
            if (ch == null) throw new IllegalStateException("no game connection found");
            Object packet = Refl.value(b.get("packet"));
            if (packet == null) throw new IllegalArgumentException("nothing to send: 'packet' resolved to null");
            String dir = Json.str(b, "dir", "out");
            if ("in".equals(dir)) TapManager.sendInbound(ch, packet);
            else TapManager.sendOutbound(ch, packet);
            Log.info("sent " + dir + "bound " + packet.getClass().getName());
            return Json.map("ok", "true", "dir", dir, "class", packet.getClass().getName());
        });

        routes.put("POST /shutdown", r -> {
            new Thread(() -> {
                try { TapManager.removeAll(); } catch (Throwable ignored) { }
                try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                server.stop(0);
                Log.info("agent control API stopped");
            }, "mcinject-stop").start();
            return Json.map("ok", "true", "note", "tap removed; the game keeps running");
        });
    }

    /** One operation inside a {@code /batch}, mirroring the standalone get/call/inspect routes. */
    private static Object runOp(Map<String, Object> b) throws Exception {
        String op = Json.str(b, "op", "call");
        Object target = Refl.value(b.get("target"));
        return switch (op) {
            case "get" -> result(Refl.getField(target, Json.str(b, "name", "")), b);
            case "call" -> result(Refl.invoke(target, Json.str(b, "method", ""),
                    Json.list(b, "args"), typeList(b)), b);
            case "inspect" -> Json.map2("class", target == null ? null : target.getClass().getName(),
                    "value", Introspect.tree(target, Introspect.Opts.of(b)));
            case "set" -> {
                Refl.setField(target, Json.str(b, "name", ""), b.get("value"));
                yield Json.map("ok", "true");
            }
            default -> throw new IllegalArgumentException("unknown batch op '" + op + "' (use get, set, call or inspect)");
        };
    }

    private static List<String> typeList(Map<String, Object> b) {
        if (!(b.get("types") instanceof List<?> l)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : l) out.add(String.valueOf(o));
        return out;
    }

    private static Object result(Object v, Map<String, Object> opts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", v == null ? null : v.getClass().getName());
        m.put("handle", v == null || Introspect.isScalar(v) ? null : Handles.of(v));
        m.put("summary", Introspect.summary(v));
        if (Json.bool(opts, "inspect", true)) {
            Introspect.Opts o = Introspect.Opts.of(opts);
            if (!opts.containsKey("depth")) o.maxDepth = 3;
            m.put("value", Introspect.tree(v, o));
        }
        return m;
    }

    /**
     * Minecraft's client is a {@link java.util.concurrent.Executor}; that interface is not obfuscated,
     * so {@code onGameThread} lets a caller run mutating game logic where the game expects it — off
     * the render thread, calls like opening a screen or clicking a slot can corrupt state or crash.
     */
    private static Object onGameThreadIfAsked(Map<String, Object> b, Call call) throws Exception {
        if (!b.containsKey("onGameThread")) return call.run();
        Object exec = Refl.value(b.get("onGameThread"));
        if (!(exec instanceof java.util.concurrent.Executor e)) {
            throw new IllegalArgumentException("onGameThread must resolve to a java.util.concurrent.Executor "
                    + "(Minecraft's client instance is one); got " + (exec == null ? "null" : exec.getClass().getName()));
        }
        Object[] res = new Object[1];
        Throwable[] err = new Throwable[1];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        e.execute(() -> {
            try { res[0] = call.run(); } catch (Throwable t) { err[0] = t; } finally { done.countDown(); }
        });
        if (!done.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("game thread did not run the task within 10s");
        }
        if (err[0] != null) throw err[0] instanceof Exception ex ? ex : new RuntimeException(err[0]);
        return res[0];
    }

    private interface Call {
        Object run() throws Exception;
    }

    // ------------------------------------------------------------------ plumbing

    private void dispatch(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if ("OPTIONS".equals(method)) { ex.sendResponseHeaders(204, -1); return; }
            if (!"/health".equals(path) && !authorized(ex)) {
                send(ex, 401, Json.write(Json.map("error", "bad or missing token; see ~/.mcinject/session.json")));
                return;
            }
            if ("/stream".equals(path)) { stream(ex); return; }

            Route route = routes.get(method + " " + path);
            if (route == null) {
                send(ex, 404, Json.write(Json.map2("error", "no route " + method + " " + path,
                        "routes", new ArrayList<>(routes.keySet()))));
                return;
            }
            Object result = route.handle(new Req(ex));
            send(ex, 200, Json.write(result));
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : t;
            Log.error(method + " " + path + " failed", c);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", c.getClass().getSimpleName() + ": " + c.getMessage());
            err.put("stack", Log.stack(c));
            try { send(ex, 500, Json.write(err)); } catch (IOException ignored) { }
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("X-MCInject-Token");
        if (h == null) {
            String q = ex.getRequestURI().getQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    if (part.startsWith("token=")) h = URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8);
                }
            }
        }
        return token.equals(h);
    }

    /** Server-sent events: the low-latency path for an assistant that reacts to packets. */
    private void stream(HttpExchange ex) throws IOException {
        long since = 0;
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            for (String part : q.split("&")) {
                if (part.startsWith("since=")) {
                    try { since = Long.parseLong(part.substring(6)); } catch (NumberFormatException ignored) { }
                }
            }
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            while (true) {
                List<Events.Event> evs = Events.await(since, 15_000);
                if (evs.isEmpty()) {
                    os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    for (Events.Event e : evs) {
                        os.write(("id: " + e.seq + "\ndata: " + e.json + "\n\n").getBytes(StandardCharsets.UTF_8));
                        since = e.seq;
                    }
                }
                os.flush();
            }
        } catch (IOException disconnected) {
            // client went away; nothing to clean up
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** Request wrapper: lazy body parsing plus query-string helpers. */
    public static final class Req {
        private final HttpExchange ex;
        private Map<String, Object> body;
        private Map<String, String> query;

        Req(HttpExchange ex) { this.ex = ex; }

        public Map<String, Object> body() throws IOException {
            if (body == null) {
                String s = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                body = s.isBlank() ? new LinkedHashMap<>() : Json.parseObject(s);
            }
            return body;
        }

        public Map<String, String> query() {
            if (query == null) {
                query = new LinkedHashMap<>();
                String q = ex.getRequestURI().getQuery();
                if (q != null) {
                    for (String part : q.split("&")) {
                        int eq = part.indexOf('=');
                        if (eq > 0) {
                            query.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                                    URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
                        }
                    }
                }
            }
            return query;
        }

        public String q(String k) { return query().get(k); }

        public int qi(String k, int def) {
            try { return Integer.parseInt(query().getOrDefault(k, String.valueOf(def))); }
            catch (NumberFormatException e) { return def; }
        }

        public long ql(String k, long def) {
            try { return Long.parseLong(query().getOrDefault(k, String.valueOf(def))); }
            catch (NumberFormatException e) { return def; }
        }

        /** The channel a mutating request targets: explicit handle, else the obvious one. */
        public Object channel() throws Exception {
            Map<String, Object> b = "GET".equals(ex.getRequestMethod()) ? Map.of() : body();
            Object spec = b.get("channel");
            if (spec != null) return Refl.value(spec);
            String h = q("channel");
            if (h != null) return Handles.require(h);
            return TapManager.primaryChannel();
        }
    }
}
