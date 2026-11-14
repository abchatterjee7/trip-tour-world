package org.aadi.triptourworld.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader/writer.
 *
 * <p>Used by the HTTP/tool/tracing layer so the agent core has no dependency on a specific Jackson
 * major version (Spring Boot 4 ships Jackson 3, whose packages differ from Jackson 2).
 * Spring MVC itself still uses Spring Boot's Jackson for request/response bodies.
 */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------ builders

    public static Map<String, Object> obj(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("obj() needs key/value pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static List<Object> arr(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    // ------------------------------------------------------------------ accessors

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List<?> ? (List<Object>) o : new ArrayList<>();
    }

    /** Walks a path of String keys / Integer indexes; returns null when anything is missing. */
    public static Object at(Object node, Object... path) {
        Object cur = node;
        for (Object p : path) {
            if (cur instanceof Map<?, ?> m && p instanceof String k) {
                cur = m.get(k);
            } else if (cur instanceof List<?> l && p instanceof Integer idx) {
                cur = (idx >= 0 && idx < l.size()) ? l.get(idx) : null;
            } else {
                return null;
            }
        }
        return cur;
    }

    /** Like {@link #at} but always returns a String ("" when missing). */
    public static String str(Object node, Object... path) {
        Object v = at(node, path);
        return v == null ? "" : String.valueOf(v);
    }

    // ------------------------------------------------------------------ LLM output helpers

    /** Returns the substring from the first '{' to the last '}', or null (mirrors the original TripBabu helper). */
    public static String extractObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** Parses the first JSON object found in free-form LLM text (handles ```json fences / chatter). */
    public static Map<String, Object> parseObjectLenient(String text) {
        String candidate = extractObject(text);
        if (candidate == null) {
            throw new IllegalArgumentException("The model did not return a JSON object.");
        }
        Object parsed = parse(candidate);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object.");
        }
        return asMap(parsed);
    }

    // ------------------------------------------------------------------ parse

    public static Object parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("Invalid JSON: null");
        }
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) {
            throw p.err("trailing characters");
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException("Invalid JSON at " + i + ": " + m);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            if (i >= s.length()) {
                throw err("unexpected end");
            }
            return switch (s.charAt(i)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw err("bad literal");
            }
            i += word.length();
            return v;
        }

        Map<String, Object> object() {
            i++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw err("expected string key");
                }
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i++) != ':') {
                    throw err("expected ':'");
                }
                ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) {
                    throw err("unterminated object");
                }
                char c = s.charAt(i++);
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return m;
                }
                throw err("expected ',' or '}'");
            }
        }

        List<Object> array() {
            i++; // [
            List<Object> l = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) {
                    throw err("unterminated array");
                }
                char c = s.charAt(i++);
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    return l;
                }
                throw err("expected ',' or ']'");
            }
        }

        String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw err("bad escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw err("bad unicode escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw err("bad unicode escape");
                        }
                        i += 4;
                    }
                    default -> throw err("bad escape");
                }
            }
        }

        Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (st == i) {
                throw err("unexpected character '" + s.charAt(i) + "'");
            }
            String t = s.substring(st, i);
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                    try {
                        return Long.parseLong(t);
                    } catch (NumberFormatException overflow) {
                        return Double.parseDouble(t);
                    }
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw err("bad number");
            }
        }
    }

    // ------------------------------------------------------------------ write

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    private static void write(Object o, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof CharSequence cs) {
            quote(cs.toString(), sb);
        } else if (o instanceof Boolean || o instanceof Integer || o instanceof Long
                || o instanceof Short || o instanceof Byte) {
            sb.append(o);
        } else if (o instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(n);
            }
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(x, sb);
            }
            sb.append(']');
        } else if (o instanceof Object[] a) {
            write(Arrays.asList(a), sb);
        } else if (o instanceof Enum<?> e) {
            quote(e.name(), sb);
        } else {
            quote(o.toString(), sb);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
