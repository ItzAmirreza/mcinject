package dev.mcinject.agent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Logs to ~/.mcinject/agent.log and to an in-memory ring the HTTP API can serve. We never log to
 * stdout: that is the game's console and Minecraft's log pipeline may not be safe to touch from
 * arbitrary threads.
 */
public final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Deque<String> RING = new ArrayDeque<>();
    private static final int RING_MAX = 500;
    private static Path file;

    static synchronized void init(Path dir) {
        try {
            Files.createDirectories(dir);
            file = dir.resolve("agent.log");
        } catch (IOException e) {
            file = null;
        }
    }

    public static synchronized void info(String msg) { line("INFO", msg); }

    public static synchronized void warn(String msg) { line("WARN", msg); }

    public static synchronized void error(String msg, Throwable t) {
        line("ERROR", msg + (t == null ? "" : "\n" + stack(t)));
    }

    public static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static void line(String level, String msg) {
        String s = LocalTime.now().format(TS) + " [" + level + "] " + msg;
        RING.addLast(s);
        while (RING.size() > RING_MAX) RING.removeFirst();
        if (file != null) {
            try {
                Files.writeString(file, s + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // logging must never break the game
            }
        }
    }

    public static synchronized List<String> tail(int n) {
        List<String> all = new ArrayList<>(RING);
        int from = Math.max(0, all.size() - n);
        return all.subList(from, all.size());
    }

    private Log() {}
}
