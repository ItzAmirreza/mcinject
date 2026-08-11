package dev.mcinject.injector;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Attaches the agent to an already-running Minecraft.
 *
 * <p>Uses the JVM's own attach mechanism, which is the whole reason no restart is needed: the target
 * JVM spins up an attach listener on demand, loads the agent jar, and calls {@code agentmain} on a
 * fresh thread while the game keeps rendering.
 */
public final class Injector {

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "help" : args[0];
        switch (cmd) {
            case "list" -> list();
            case "attach" -> attach(args);
            case "help", "-h", "--help" -> usage();
            default -> {
                System.err.println("unknown command: " + cmd);
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                mcinject injector

                  list                       show attachable JVMs, marking likely Minecraft clients
                  attach [options]           load the agent into a running game

                attach options:
                  --pid <n>       target process (default: auto-detect the Minecraft client)
                  --jar <path>    agent jar (default: dist/mcinject-agent.jar next to this jar)
                  --port <n>      control API port (default 0 = pick a free one)
                  --token <s>     control API token (default: random)
                  --no-autotap    attach without installing the packet tap
                """);
    }

    // ------------------------------------------------------------------

    private record Candidate(long pid, String command, boolean minecraft) {}

    private static List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            Optional<String> cmd = ph.info().command();
            if (cmd.isEmpty() || !cmd.get().contains("java")) continue;
            String full = ph.info().commandLine().orElse(cmd.get());
            String lower = full.toLowerCase(Locale.ROOT);
            boolean mc = lower.contains("--assetindex") || lower.contains("net.minecraft.client")
                    || lower.contains("minecraft.launcher") || lower.contains("knot")
                    || lower.contains("--gamedir");
            out.add(new Candidate(ph.pid(), full, mc));
        }
        return out;
    }

    private static void list() {
        System.out.println("Attachable JVMs (via the attach API):");
        for (VirtualMachineDescriptor d : VirtualMachine.list()) {
            System.out.printf("  %-8s %s%n", d.id(), d.displayName());
        }
        System.out.println("\nJava processes seen by the OS:");
        for (Candidate c : candidates()) {
            System.out.printf("  %-8d %s %s%n", c.pid(), c.minecraft() ? "[MINECRAFT]" : "           ",
                    clip(c.command(), 150));
        }
    }

    private static void attach(String[] args) throws Exception {
        long pid = -1;
        Path jar = defaultJar();
        String port = "0";
        String token = null;
        boolean autotap = true;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--pid" -> pid = Long.parseLong(args[++i]);
                case "--jar" -> jar = Path.of(args[++i]);
                case "--port" -> port = args[++i];
                case "--token" -> token = args[++i];
                case "--no-autotap" -> autotap = false;
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        if (pid < 0) {
            List<Candidate> mc = candidates().stream().filter(Candidate::minecraft).toList();
            if (mc.isEmpty()) {
                System.err.println("No running Minecraft client found. Start the game, or pass --pid explicitly.");
                System.err.println("Run 'list' to see every Java process.");
                System.exit(1);
            }
            if (mc.size() > 1) {
                System.err.println("Several Minecraft-looking processes are running; pick one with --pid:");
                for (Candidate c : mc) System.err.printf("  %d  %s%n", c.pid(), clip(c.command(), 150));
                System.exit(1);
            }
            pid = mc.get(0).pid();
            System.out.println("Auto-detected Minecraft at pid " + pid);
        }

        if (!Files.isRegularFile(jar)) {
            System.err.println("Agent jar not found at " + jar.toAbsolutePath() + " — run ./build.sh first.");
            System.exit(1);
        }

        StringBuilder opts = new StringBuilder("port=").append(port);
        if (token != null) opts.append(",token=").append(token);
        if (!autotap) opts.append(",autotap=false");
        Path core = jar.toAbsolutePath().getParent().resolve("mcinject-core.jar");
        if (!Files.isRegularFile(core)) {
            System.err.println("Core jar not found at " + core + " — run ./build.sh first.");
            System.exit(1);
        }
        opts.append(",core=").append(core);

        // Attach from a uniquely-named copy. The system classloader indexes an appended jar once and
        // caches its entry list, so re-attaching the same path after a rebuild raises
        // ClassNotFoundException for anything new in it. A fresh path avoids that entirely; the
        // bootstrap class itself is only loaded from the first one, which is exactly what we want.
        Path staging = Path.of(System.getProperty("user.home"), ".mcinject", "attach");
        Files.createDirectories(staging);
        Path unique = staging.resolve("mcinject-agent-" + System.currentTimeMillis() + ".jar");
        Files.copy(jar, unique);

        System.out.println("Attaching " + jar.getFileName() + " to pid " + pid + " ...");
        VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
        try {
            vm.loadAgent(unique.toAbsolutePath().toString(), opts.toString());
            System.out.println("Agent loaded.");
        } finally {
            vm.detach();
        }

        Path session = Path.of(System.getProperty("user.home"), ".mcinject", "session.json");
        for (int i = 0; i < 50; i++) {
            if (Files.isRegularFile(session)) {
                String s = Files.readString(session, StandardCharsets.UTF_8);
                if (s.contains("\"pid\":\"" + pid + "\"")) {
                    System.out.println("Control API ready: " + s);
                    return;
                }
            }
            Thread.sleep(100);
        }
        System.out.println("Agent loaded, but " + session + " has not appeared yet. Check ~/.mcinject/agent.log.");
    }

    private static Path defaultJar() {
        try {
            Path self = Path.of(Injector.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path sibling = self.getParent().resolve("mcinject-agent.jar");
            if (Files.isRegularFile(sibling)) return sibling;
        } catch (Exception ignored) {
            // fall through to the relative default
        }
        return Path.of("dist", "mcinject-agent.jar");
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private Injector() {}
}
