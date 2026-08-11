package dev.mcinject.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free JSON writer/parser. The agent runs inside the game JVM, so it can't pull in Gson. */
public final class Json {

    // ---------------- writing ----------------

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder(256);
        writeTo(sb, o);
        return sb.toString();
    }

    /** Already-serialized JSON, spliced in verbatim. Lets us cache introspection results as text. */
    public record Raw(String json) {}

    @SuppressWarnings("unchecked")
    private static void writeTo(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Raw r) { sb.append(r.json() == null ? "null" : r.json()); return; }
        if (o instanceof String s) { str(sb, s); return; }
        if (o instanceof Boolean b) { sb.append(b.booleanValue()); return; }
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) str(sb, String.valueOf(d));
            else sb.append(d);
            return;
        }
        if (o instanceof Number n) { sb.append(n.toString()); return; }
        if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object e : it) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, e);
            }
            sb.append(']');
            return;
        }
        if (o.getClass().isArray()) {
            sb.append('[');
            int len = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                writeTo(sb, java.lang.reflect.Array.get(o, i));
            }
            sb.append(']');
            return;
        }
        str(sb, String.valueOf(o));
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------- parsing ----------------

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        Object o = parse(s);
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("expected a JSON object, got " + (o == null ? "null" : o.getClass().getSimpleName()));
    }

    private static final class P {
        private final String s;
        private int i;

        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            if (i >= s.length()) throw err("unexpected end of input");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> string();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> number();
            };
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw err("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("expected , or } but got " + c);
            }
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) throw err("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("expected , or ] but got " + c);
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> throw err("bad escape \\" + e);
                }
            }
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(start, i);
            if (t.isEmpty()) throw err("expected a value");
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                try { return Long.valueOf(t); } catch (NumberFormatException ignored) { /* fall through */ }
            }
            return Double.valueOf(t);
        }

        Object lit(String word, Object v) {
            if (!s.startsWith(word, i)) throw err("expected " + word);
            i += word.length();
            return v;
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw err("expected " + c);
            i++;
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("JSON at offset " + i + ": " + msg);
        }
    }

    // ---------------- convenience accessors ----------------

    public static String str(Map<String, ?> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    /** Tolerant of both JSON numbers and query-string text, so one accessor serves both. */
    public static int i(Map<String, ?> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return def; }
        }
        return def;
    }

    public static boolean bool(Map<String, ?> m, String k, boolean def) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return s.equalsIgnoreCase("true") || s.equals("1");
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, ?> m, String k) {
        Object v = m.get(k);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    /** String-valued convenience builder. */
    public static Map<String, Object> map(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Same, but values may be anything JSON-writable. */
    public static Map<String, Object> map2(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private Json() {}
}
