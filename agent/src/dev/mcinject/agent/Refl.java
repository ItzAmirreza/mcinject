package dev.mcinject.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The generic reflection layer an LLM actually operates through.
 *
 * <p>Because Minecraft's names are obfuscated, the workflow is: search the live object graph for a
 * value you recognise (a player name, an item's text), get back a handle and a path, then read
 * fields or call methods on it. Everything here is name-agnostic by design.
 */
public final class Refl {

    // ---------------------------------------------------------------- value specs

    /**
     * Turns a JSON value spec into a live object. Plain JSON scalars pass through; objects with a
     * single directive key are resolved recursively, which is what lets one request say "call
     * method X on the object at path Y with an enum constant Z".
     */
    @SuppressWarnings("unchecked")
    public static Object value(Object spec) throws Exception {
        if (spec == null || spec instanceof Boolean || spec instanceof String) return spec;
        if (spec instanceof Number) return spec;
        if (spec instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(value(o));
            return out;
        }
        if (!(spec instanceof Map)) return spec;
        Map<String, Object> m = (Map<String, Object>) spec;

        if (m.containsKey("h")) return Handles.require(String.valueOf(m.get("h")));
        if (m.containsKey("path")) return path(String.valueOf(m.get("path")));
        if (m.containsKey("seq")) {
            PacketStore.Rec r = PacketStore.get(((Number) m.get("seq")).longValue());
            if (r == null) throw new IllegalArgumentException("no packet with seq " + m.get("seq"));
            if (r.obj == null) throw new IllegalArgumentException("packet " + r.seq + " is no longer retained");
            return r.obj;
        }
        if (m.containsKey("class")) return findClass(String.valueOf(m.get("class")));
        if (m.containsKey("null")) return null;
        if (m.containsKey("enum")) {
            Map<String, Object> e = (Map<String, Object>) m.get("enum");
            Class<?> c = findClass(String.valueOf(e.get("class")));
            String name = String.valueOf(e.get("name"));
            for (Object k : c.getEnumConstants()) if (((Enum<?>) k).name().equals(name)) return k;
            // obfuscated enums keep real names only sometimes; allow ordinal fallback
            if (e.get("ordinal") instanceof Number n) return c.getEnumConstants()[n.intValue()];
            throw new IllegalArgumentException("no enum constant " + name + " in " + c.getName()
                    + " (have " + Arrays.toString(c.getEnumConstants()) + ")");
        }
        if (m.containsKey("cast")) {
            Map<String, Object> c = (Map<String, Object>) m.get("cast");
            return coerce(value(c.get("value")), primitive(String.valueOf(c.get("type"))));
        }
        if (m.containsKey("new")) {
            Map<String, Object> n = (Map<String, Object>) m.get("new");
            return construct(String.valueOf(n.get("class")), Json.list(n, "args"), typeNames(n.get("types")));
        }
        if (m.containsKey("field")) {
            Map<String, Object> f = (Map<String, Object>) m.get("field");
            return getField(value(f.get("target")), String.valueOf(f.get("name")));
        }
        if (m.containsKey("call")) {
            Map<String, Object> c = (Map<String, Object>) m.get("call");
            return invoke(value(c.get("target")), String.valueOf(c.get("method")),
                    Json.list(c, "args"), typeNames(c.get("types")));
        }
        if (m.containsKey("array")) {
            Map<String, Object> a = (Map<String, Object>) m.get("array");
            Class<?> comp = classFor(String.valueOf(a.get("type")));
            List<Object> items = Json.list(a, "items");
            Object arr = Array.newInstance(comp, items.size());
            for (int i = 0; i < items.size(); i++) Array.set(arr, i, coerce(value(items.get(i)), comp));
            return arr;
        }
        return spec;
    }

    private static List<String> typeNames(Object o) {
        if (!(o instanceof List<?> l)) return null;
        List<String> out = new ArrayList<>();
        for (Object e : l) out.add(String.valueOf(e));
        return out;
    }

    // ---------------------------------------------------------------- paths

    /**
     * Resolves an expression such as {@code h42.field_7512.slots[9].item}. Roots are a handle
     * ({@code hN}), {@code primary} (the tapped connection's channel), {@code channels[i]}, or
     * {@code static:some.Class.FIELD}.
     */
    public static Object path(String expr) throws Exception {
        String s = expr.trim();
        Object cur;
        int i;

        if (s.startsWith("static:")) {
            // '#' separates class from field so a dotted class name stays unambiguous
            int hash = s.indexOf('#');
            if (hash < 0) throw new IllegalArgumentException("static: needs the form static:some.Class#FIELD");
            Class<?> c = findClass(s.substring("static:".length(), hash));
            int end = hash + 1;
            while (end < s.length() && s.charAt(end) != '.' && s.charAt(end) != '[') end++;
            cur = staticField(c, s.substring(hash + 1, end));
            i = end;
        } else if (s.startsWith("primary")) {
            cur = TapManager.primaryChannel();
            if (cur == null) throw new IllegalStateException("no game connection found");
            i = "primary".length();
        } else if (s.startsWith("channels")) {
            cur = Discovery.findChannels();
            i = "channels".length();
        } else if (s.startsWith("h") && s.length() > 1 && Character.isDigit(s.charAt(1))) {
            int end = 1;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            cur = Handles.require(s.substring(0, end));
            i = end;
        } else {
            throw new IllegalArgumentException("path must start with hN, primary, channels or static:Class.FIELD");
        }

        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < s.length() && s.charAt(end) != '.' && s.charAt(end) != '[') end++;
                String name = s.substring(i + 1, end);
                if (name.endsWith("()")) cur = invoke(cur, name.substring(0, name.length() - 2), List.of(), null);
                else cur = getField(cur, name);
                i = end;
            } else if (c == '[') {
                int end = s.indexOf(']', i);
                if (end < 0) throw new IllegalArgumentException("unclosed [ in path");
                String key = s.substring(i + 1, end);
                cur = index(cur, key);
                i = end + 1;
            } else {
                throw new IllegalArgumentException("unexpected '" + c + "' at offset " + i + " of path");
            }
            if (cur == null) return null;
        }
        return cur;
    }

    private static Object index(Object o, String key) throws Exception {
        if (o == null) return null;
        if (o.getClass().isArray()) return Array.get(o, Integer.parseInt(key));
        if (o instanceof List<?> l) return l.get(Integer.parseInt(key));
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) if (String.valueOf(e.getKey()).equals(key)) return e.getValue();
            return null;
        }
        // fastutil and friends: fall back to get(int) / get(Object)
        try { return Discovery.call(o, "get", Integer.parseInt(key)); } catch (Throwable ignored) { }
        return Discovery.call(o, "get", key);
    }

    // ---------------------------------------------------------------- fields

    public static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep climbing
            }
        }
        return null;
    }

    public static Object getField(Object target, String name) throws Exception {
        if (target == null) throw new IllegalArgumentException("cannot read field '" + name + "' of null");
        Class<?> c = target instanceof Class<?> k ? k : target.getClass();
        Field f = findField(c, name);
        if (f == null) {
            // records expose accessors, not always fields under obfuscation
            try { return Discovery.call(target, name); } catch (Throwable ignored) { }
            throw new NoSuchFieldException(c.getName() + "." + name + " (available: " + fieldNames(c) + ")");
        }
        return f.get(Modifier.isStatic(f.getModifiers()) ? null : target);
    }

    public static void setField(Object target, String name, Object rawValue) throws Exception {
        Class<?> c = target instanceof Class<?> k ? k : target.getClass();
        Field f = findField(c, name);
        if (f == null) throw new NoSuchFieldException(c.getName() + "." + name + " (available: " + fieldNames(c) + ")");
        Object v = coerce(value(rawValue), f.getType());
        if (Modifier.isFinal(f.getModifiers())) {
            // final instance fields are still writable through the trusted lookup an agent can obtain
            var lookup = java.lang.invoke.MethodHandles.privateLookupIn(f.getDeclaringClass(), java.lang.invoke.MethodHandles.lookup());
            var vh = lookup.unreflectVarHandle(f);
            if (Modifier.isStatic(f.getModifiers())) vh.set(v);
            else vh.set(target, v);
            return;
        }
        f.set(Modifier.isStatic(f.getModifiers()) ? null : target, v);
    }

    public static Object staticField(Class<?> c, String name) throws Exception {
        Field f = findField(c, name);
        if (f == null) throw new NoSuchFieldException(c.getName() + "." + name);
        return f.get(null);
    }

    /**
     * Field names for a "no such field" message, capped hard. An entity class can declare several
     * hundred fields across its hierarchy, and dumping all of them turns a small mistake into a
     * response that swamps the caller's context. Instance fields come first because they're what a
     * caller almost always meant; mc_describe_class gives the complete picture when it's needed.
     */
    private static final int MAX_SUGGESTIONS = 60;

    private static String fieldNames(Class<?> c) {
        List<String> instance = new ArrayList<>();
        for (Field f : Introspect.fieldsOf(c, false)) instance.add(f.getName());
        List<String> statics = new ArrayList<>();
        for (Field f : Introspect.fieldsOf(c, true)) statics.add("static " + f.getName());

        int total = instance.size() + statics.size();
        List<String> shown = new ArrayList<>(instance.subList(0, Math.min(instance.size(), MAX_SUGGESTIONS)));
        if (shown.size() < MAX_SUGGESTIONS) {
            shown.addAll(statics.subList(0, Math.min(statics.size(), MAX_SUGGESTIONS - shown.size())));
        }
        String s = String.join(", ", shown);
        return total > shown.size()
                ? s + ", … (" + (total - shown.size()) + " more; use /class for the full list)"
                : s;
    }

    // ---------------------------------------------------------------- methods

    public static Object invoke(Object target, String method, List<Object> argSpecs, List<String> types) throws Exception {
        if (target == null) throw new IllegalArgumentException("cannot call '" + method + "' on null");
        boolean isStatic = target instanceof Class<?>;
        Class<?> c = isStatic ? (Class<?>) target : target.getClass();

        Object[] args = new Object[argSpecs == null ? 0 : argSpecs.size()];
        for (int i = 0; i < args.length; i++) args[i] = value(argSpecs.get(i));

        List<Method> cands = ranked(allMethods(c, method, args.length), args, types);
        if (cands.isEmpty()) {
            throw new NoSuchMethodException(c.getName() + "." + method + "/" + args.length
                    + " — candidates: " + signatures(allMethods(c, method, -1)));
        }

        // Try candidates in order. The same method often appears on both a non-exported
        // implementation class and an exported interface (sun.instrument.InstrumentationImpl vs
        // java.lang.instrument.Instrumentation); only the interface one is callable from here, and
        // which is which isn't knowable up front, so fall through on access failures.
        IllegalAccessException lastAccessFailure = null;
        for (Method m : cands) {
            try { m.setAccessible(true); } catch (Throwable notPermitted) { /* may still be public */ }
            try {
                return m.invoke(isStatic ? null : target, coerceAll(args, m.getParameterTypes()));
            } catch (IllegalAccessException e) {
                lastAccessFailure = e;
            }
        }
        throw lastAccessFailure != null ? lastAccessFailure
                : new NoSuchMethodException(c.getName() + "." + method + "/" + args.length);
    }

    /** Best-first ordering: exact type match, then genuinely reachable declaring classes. */
    private static List<Method> ranked(List<Method> cands, Object[] args, List<String> types) {
        List<Method> out = new ArrayList<>(cands);
        out.sort((a, b) -> {
            int ta = typeScore(a, types), tb = typeScore(b, types);
            if (ta != tb) return tb - ta;
            int aa = accessScore(a), ab = accessScore(b);
            if (aa != ab) return ab - aa;
            return Boolean.compare(!compatible(a.getParameterTypes(), args), !compatible(b.getParameterTypes(), args));
        });
        if (types != null && !types.isEmpty()) out.removeIf(m -> typeScore(m, types) == 0);
        return out;
    }

    private static int typeScore(Method m, List<String> types) {
        if (types == null || types.isEmpty()) return 1;
        Class<?>[] p = m.getParameterTypes();
        if (p.length != types.size()) return 0;
        for (int i = 0; i < p.length; i++) {
            if (!p[i].getName().equals(types.get(i)) && !p[i].getSimpleName().equals(types.get(i))) return 0;
        }
        return 2;
    }

    private static int accessScore(Method m) {
        Class<?> d = m.getDeclaringClass();
        int score = 0;
        if (Modifier.isPublic(m.getModifiers())) score++;
        if (Modifier.isPublic(d.getModifiers())) score++;
        Module mod = d.getModule();
        String pkg = d.getPackageName();
        if (mod == null || !mod.isNamed() || mod.isExported(pkg)) score += 2;
        if (d.isInterface()) score++;
        return score;
    }

    public static Object construct(String className, List<Object> argSpecs, List<String> types) throws Exception {
        Class<?> c = findClass(className);
        Object[] args = new Object[argSpecs == null ? 0 : argSpecs.size()];
        for (int i = 0; i < args.length; i++) args[i] = value(argSpecs.get(i));
        List<Constructor<?>> cands = new ArrayList<>();
        for (Constructor<?> k : c.getDeclaredConstructors()) if (k.getParameterCount() == args.length) cands.add(k);
        Constructor<?> chosen = (Constructor<?>) pickExec(cands, args, types);
        if (chosen == null) {
            throw new NoSuchMethodException("no constructor " + className + "/" + args.length
                    + " — candidates: " + signatures(List.of(c.getDeclaredConstructors())));
        }
        chosen.setAccessible(true);
        return chosen.newInstance(coerceAll(args, chosen.getParameterTypes()));
    }

    private static List<Method> allMethods(Class<?> c, String name, int arity) {
        List<Method> out = new ArrayList<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) collect(k, name, arity, out);
        for (Class<?> i : allInterfaces(c)) collect(i, name, arity, out);
        return out;
    }

    private static void collect(Class<?> k, String name, int arity, List<Method> out) {
        Method[] ms;
        try { ms = k.getDeclaredMethods(); } catch (Throwable t) { return; }
        for (Method m : ms) {
            if (!m.getName().equals(name)) continue;
            if (arity >= 0 && m.getParameterCount() != arity) continue;
            out.add(m);
        }
    }

    private static List<Class<?>> allInterfaces(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                if (out.contains(i)) continue;
                out.add(i);
                out.addAll(allInterfaces(i));
            }
        }
        return out;
    }

    private static Executable pickExec(List<? extends Executable> cands, Object[] args, List<String> types) throws Exception {
        if (cands.isEmpty()) return null;
        if (types != null && !types.isEmpty()) {
            for (Executable e : cands) {
                Class<?>[] p = e.getParameterTypes();
                if (p.length != types.size()) continue;
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (!p[i].getName().equals(types.get(i)) && !p[i].getSimpleName().equals(types.get(i))) { ok = false; break; }
                }
                if (ok) return e;
            }
            return null;
        }
        if (cands.size() == 1) return cands.get(0);
        for (Executable e : cands) if (compatible(e.getParameterTypes(), args)) return e;
        return cands.get(0);
    }

    private static boolean compatible(Class<?>[] p, Object[] args) {
        if (p.length != args.length) return false;
        for (int i = 0; i < p.length; i++) {
            Object a = args[i];
            if (a == null) { if (p[i].isPrimitive()) return false; continue; }
            Class<?> want = box(p[i]);
            if (want.isInstance(a)) continue;
            if (a instanceof Number && Number.class.isAssignableFrom(want)) continue;
            if (a instanceof Number && want == Character.class) continue;
            return false;
        }
        return true;
    }

    private static String signatures(List<? extends Executable> es) {
        List<String> out = new ArrayList<>();
        for (Executable e : es) {
            List<String> ps = new ArrayList<>();
            for (Class<?> p : e.getParameterTypes()) ps.add(p.getSimpleName());
            out.add(e.getName() + "(" + String.join(", ", ps) + ")");
            if (out.size() > 40) break;
        }
        return String.join(" | ", out);
    }

    // ---------------------------------------------------------------- coercion

    private static Object[] coerceAll(Object[] args, Class<?>[] types) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) out[i] = coerce(args[i], types[i]);
        return out;
    }

    public static Object coerce(Object v, Class<?> want) {
        if (want == null || v == null) return v;
        if (want.isInstance(v) && !want.isPrimitive()) return v;
        if (v instanceof Number n) {
            if (want == int.class || want == Integer.class) return n.intValue();
            if (want == long.class || want == Long.class) return n.longValue();
            if (want == short.class || want == Short.class) return n.shortValue();
            if (want == byte.class || want == Byte.class) return n.byteValue();
            if (want == double.class || want == Double.class) return n.doubleValue();
            if (want == float.class || want == Float.class) return n.floatValue();
            if (want == char.class || want == Character.class) return (char) n.intValue();
            if (want == boolean.class || want == Boolean.class) return n.intValue() != 0;
        }
        if (v instanceof String s) {
            if (want == char.class || want == Character.class) return s.isEmpty() ? '\0' : s.charAt(0);
            if (want.isEnum()) {
                for (Object k : want.getEnumConstants()) if (((Enum<?>) k).name().equals(s)) return k;
            }
        }
        if (v instanceof Boolean b && (want == boolean.class || want == Boolean.class)) return b;
        if (v instanceof List<?> l && want.isArray()) {
            Class<?> comp = want.getComponentType();
            Object arr = Array.newInstance(comp, l.size());
            for (int i = 0; i < l.size(); i++) Array.set(arr, i, coerce(l.get(i), comp));
            return arr;
        }
        return v;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == char.class) return Character.class;
        if (c == boolean.class) return Boolean.class;
        return c;
    }

    private static Class<?> primitive(String n) {
        return switch (n) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "short" -> short.class;
            case "byte" -> byte.class;
            case "double" -> double.class;
            case "float" -> float.class;
            case "char" -> char.class;
            case "boolean" -> boolean.class;
            default -> throw new IllegalArgumentException("not a primitive type: " + n);
        };
    }

    private static Class<?> classFor(String n) throws Exception {
        try { return primitive(n); } catch (IllegalArgumentException notPrimitive) { return findClass(n); }
    }

    // ---------------------------------------------------------------- class lookup

    /** Searches every loader we know about, including classes already loaded in the game. */
    public static Class<?> findClass(String name) throws ClassNotFoundException {
        for (ClassLoader cl : AgentMain.candidateLoaders()) {
            if (cl == null) continue;
            try { return Class.forName(name, false, cl); } catch (Throwable ignored) { }
        }
        Class<?>[] loaded = AgentMain.loadedClasses();
        if (loaded != null) for (Class<?> c : loaded) if (c.getName().equals(name)) return c;
        throw new ClassNotFoundException(name);
    }

    public static List<String> listClasses(String contains, int limit) {
        List<String> out = new ArrayList<>();
        Class<?>[] loaded = AgentMain.loadedClasses();
        if (loaded == null) return out;
        String needle = contains == null ? "" : contains.toLowerCase(Locale.ROOT);
        for (Class<?> c : loaded) {
            String n = c.getName();
            if (needle.isEmpty() || n.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(n);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    public static Map<String, Object> describeClass(String name) throws Exception {
        Class<?> c = findClass(name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.getName());
        m.put("superclass", c.getSuperclass() == null ? null : c.getSuperclass().getName());
        m.put("record", c.isRecord());
        m.put("enum", c.isEnum());
        List<String> ifaces = new ArrayList<>();
        for (Class<?> i : c.getInterfaces()) ifaces.add(i.getName());
        m.put("interfaces", ifaces);
        if (c.isEnum()) {
            List<String> consts = new ArrayList<>();
            for (Object k : c.getEnumConstants()) consts.add(((Enum<?>) k).name());
            m.put("enumConstants", consts);
        }
        List<String> fields = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            fields.add((Modifier.isStatic(f.getModifiers()) ? "static " : "") + f.getType().getSimpleName() + " " + f.getName());
        }
        m.put("fields", fields);
        List<String> methods = new ArrayList<>();
        for (Method x : c.getDeclaredMethods()) {
            List<String> ps = new ArrayList<>();
            for (Class<?> p : x.getParameterTypes()) ps.add(p.getSimpleName());
            methods.add((Modifier.isStatic(x.getModifiers()) ? "static " : "") + x.getReturnType().getSimpleName()
                    + " " + x.getName() + "(" + String.join(", ", ps) + ")");
        }
        m.put("methods", methods);
        List<String> ctors = new ArrayList<>();
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            List<String> ps = new ArrayList<>();
            for (Class<?> p : k.getParameterTypes()) ps.add(p.getName());
            ctors.add("(" + String.join(", ", ps) + ")");
        }
        m.put("constructors", ctors);
        return m;
    }

    private Refl() {}
}
