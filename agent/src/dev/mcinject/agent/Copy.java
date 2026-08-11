package dev.mcinject.agent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Clone-with-overrides: take a packet the client really sent, change one component, send it again.
 *
 * <p>This is the most reliable way to write packets under obfuscation. You never need to know what
 * class {@code class_2813} is or what its constructor means — you observe a genuine packet of that
 * shape, swap the one value you care about (a slot index, a message string), and let the game's own
 * encoder serialize it. Most modern Minecraft packets are records, which makes the copy exact.
 */
public final class Copy {

    public static Object cloneWith(Object src, Map<String, Object> overrides) throws Exception {
        if (src == null) throw new IllegalArgumentException("nothing to clone");
        Class<?> c = src.getClass();
        return c.isRecord() ? cloneRecord(src, c, overrides) : cloneObject(src, c, overrides);
    }

    private static Object cloneRecord(Object src, Class<?> c, Map<String, Object> overrides) throws Exception {
        RecordComponent[] comps = c.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        List<String> unused = new ArrayList<>(overrides.keySet());

        for (int i = 0; i < comps.length; i++) {
            RecordComponent rc = comps[i];
            types[i] = rc.getType();
            Object current;
            var acc = rc.getAccessor();
            acc.setAccessible(true);
            current = acc.invoke(src);
            if (overrides.containsKey(rc.getName())) {
                current = Refl.coerce(Refl.value(overrides.get(rc.getName())), rc.getType());
                unused.remove(rc.getName());
            }
            args[i] = current;
        }
        if (!unused.isEmpty()) {
            throw new IllegalArgumentException("no such record component(s) " + unused
                    + " on " + c.getName() + " — components are " + names(comps));
        }
        Constructor<?> canonical = c.getDeclaredConstructor(types);
        canonical.setAccessible(true);
        return canonical.newInstance(args);
    }

    private static Object cloneObject(Object src, Class<?> c, Map<String, Object> overrides) throws Exception {
        Object copy = allocate(c);
        List<String> unused = new ArrayList<>(overrides.keySet());
        for (Field f : Introspect.fieldsOf(c, false)) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Object v = f.get(src);
            if (overrides.containsKey(f.getName())) {
                v = Refl.coerce(Refl.value(overrides.get(f.getName())), f.getType());
                unused.remove(f.getName());
            }
            setField(copy, f, v);
        }
        if (!unused.isEmpty()) throw new IllegalArgumentException("no such field(s) " + unused + " on " + c.getName());
        return copy;
    }

    private static void setField(Object target, Field f, Object v) throws Exception {
        if (Modifier.isFinal(f.getModifiers())) {
            var lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                    f.getDeclaringClass(), java.lang.invoke.MethodHandles.lookup());
            lookup.unreflectVarHandle(f).set(target, v);
        } else {
            f.set(target, v);
        }
    }

    /** No-constructor allocation, so classes with validating or side-effecting constructors still copy. */
    private static Object allocate(Class<?> c) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        var allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, c);
    }

    private static List<String> names(RecordComponent[] comps) {
        List<String> out = new ArrayList<>();
        for (RecordComponent rc : comps) out.add(rc.getName() + ": " + rc.getType().getSimpleName());
        return out;
    }

    private Copy() {}
}
