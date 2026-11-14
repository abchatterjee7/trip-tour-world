package org.aadi.triptourworld.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal {@code .env} reader. Understands the formats people actually write:
 * <pre>
 * KEY=value
 * KEY = "value"        # spaces around '=' and quotes are fine
 * export KEY='value'
 * # comments and blank lines are ignored
 * </pre>
 * Values are exposed as JVM system properties and never override real environment variables, so
 * production platforms (Render, Hostinger, Docker, Kubernetes) stay in control.
 */
public final class DotenvLoader {
    private DotenvLoader() {}

    /** Loads the first existing candidate file; returns the number of keys applied. */
    public static int load(Path... candidates) {
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) {
                try {
                    return apply(parse(Files.readAllLines(p, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    System.err.println("WARN: could not read " + p + ": " + e.getMessage());
                }
            }
        }
        return 0;
    }

    public static int apply(Map<String, String> values) {
        int applied = 0;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (System.getenv(e.getKey()) == null && System.getProperty(e.getKey()) == null) {
                System.setProperty(e.getKey(), e.getValue());
                applied++;
            }
        }
        return applied;
    }

    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring(7).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            out.put(key, unquote(value));
        }
        return out;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
            char q = v.charAt(0);
            int close = v.indexOf(q, 1);
            if (close > 0) {
                return v.substring(1, close); // anything after the closing quote (e.g. a comment) is dropped
            }
        }
        int hash = v.indexOf(" #"); // inline comment on an unquoted value
        return (hash >= 0 ? v.substring(0, hash) : v).strip();
    }
}
