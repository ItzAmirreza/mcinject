package dev.mcinject.boot;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A deliberately tiny, deliberately permanent bootstrap.
 *
 * <p>Agent jars are appended to the system classpath, and a class the system loader has already
 * loaded is never reloaded — so re-attaching an updated agent would silently keep running the old
 * code, and {@code Instrumentation.redefineClasses} can't add methods or classes. Both would leave
 * "restart Minecraft to pick up the fix" as the only option, which is exactly what this project
 * exists to avoid.
 *
 * <p>So the agent jar contains only this class. The real implementation lives in a separate core jar
 * that is <em>not</em> on the system classpath, loaded through a fresh {@link URLClassLoader} on every
 * attach. Re-attach and you get genuinely new classes; the previous instance is asked to shut down
 * first so its tap comes out of the pipeline cleanly. Only this file has to stay stable across the
 * life of the game process.
 */
public final class Boot {

    /** Held across reloads so each attach can retire the previous generation. */
    private static volatile ClassLoader previousLoader;
    private static volatile Class<?> previousAgent;
    private static volatile int generation = 0;

    public static void premain(String args, Instrumentation inst) { agentmain(args, inst); }

    public static synchronized void agentmain(String args, Instrumentation inst) {
        try {
            retirePrevious();

            Path core = locateCore(args);
            if (core == null || !Files.isRegularFile(core)) {
                System.err.println("[mcinject] core jar not found" + (core == null ? "" : " at " + core)
                        + "; pass core=/path/to/mcinject-core.jar in the agent options");
                return;
            }

            generation++;
            URLClassLoader loader = new CoreLoader(generation, core.toUri().toURL(), Boot.class.getClassLoader());

            Class<?> agent = Class.forName("dev.mcinject.agent.AgentMain", true, loader);
            Method entry = agent.getMethod("agentmain", String.class, Instrumentation.class);
            String withGen = (args == null || args.isBlank() ? "" : args + ",") + "generation=" + generation;
            entry.invoke(null, withGen, inst);

            previousLoader = loader;
            previousAgent = agent;
        } catch (Throwable t) {
            System.err.println("[mcinject] bootstrap failed: " + t);
            t.printStackTrace();
        }
    }

    private static void retirePrevious() {
        Class<?> prev = previousAgent;
        if (prev == null) return;
        try {
            prev.getMethod("shutdownForReload").invoke(null);
        } catch (Throwable t) {
            System.err.println("[mcinject] previous generation did not shut down cleanly: " + t);
        }
        try {
            if (previousLoader instanceof URLClassLoader u) u.close();
        } catch (Throwable ignored) {
            // the old loader stays alive until its threads finish; that's fine
        }
        previousAgent = null;
        previousLoader = null;
    }

    /**
     * Child-first for the agent's own packages, parent-first for everything else.
     *
     * <p>Ordinary parent-first delegation would defeat the whole design here: an earlier build's
     * classes can already be sitting in the system classloader (every attach appends its jar to it),
     * and the parent would keep winning, so a "reload" would silently rerun the old code. JDK and
     * game classes still come from the parent, which is what keeps {@code Instrumentation} and Netty
     * types identical across generations.
     */
    private static final class CoreLoader extends URLClassLoader {
        CoreLoader(int generation, URL core, ClassLoader parent) {
            super("mcinject-gen" + generation, new URL[]{core}, parent);
        }

        /**
         * Resources are child-first for the same reason classes are, and it bites harder: the stale
         * first-generation jar is still indexed by the system loader, so a parent-first lookup hands
         * back a URL into a jar whose contents have since been replaced. Opening it fails and the
         * caller just sees null.
         */
        @Override
        public URL getResource(String name) {
            if (name.startsWith("mcinject/") || name.startsWith("dev/mcinject/")) {
                URL own = findResource(name);
                if (own != null) return own;
            }
            return super.getResource(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("dev.mcinject.agent.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException notOurs) {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    /** Core jar location: explicit {@code core=} option, else the file sitting next to this jar. */
    private static Path locateCore(String args) {
        if (args != null) {
            for (String part : args.split(",")) {
                if (part.startsWith("core=")) return Path.of(part.substring(5).trim());
            }
        }
        try {
            File self = new File(Boot.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return self.toPath().getParent().resolve("mcinject-core.jar");
        } catch (Exception e) {
            return null;
        }
    }

    private Boot() {}
}
