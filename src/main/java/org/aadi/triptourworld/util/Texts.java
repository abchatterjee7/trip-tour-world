package org.aadi.triptourworld.util;

/** Small string helpers shared by agents, tools and guardrails. */
public final class Texts {
    private Texts() {}

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public static String orElse(String s, String fallback) {
        return blank(s) ? fallback : s;
    }

    /** Truncates to {@code max} characters, adding an ellipsis marker when something was cut. */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Replaces {{key}} placeholders (safe with '%' and '$' characters, unlike String.format / replaceAll). */
    public static String render(String template, String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("render() needs key/value pairs");
        }
        String out = template;
        for (int i = 0; i < kv.length; i += 2) {
            out = out.replace("{{" + kv[i] + "}}", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return out;
    }
}
