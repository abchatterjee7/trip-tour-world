package org.aadi.triptourworld.config;

import org.aadi.triptourworld.util.Texts;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Immutable runtime configuration, read from environment variables (12-factor).
 *
 * <p>Locally, {@link DotenvLoader} copies values from a {@code .env} file into JVM system properties
 * first; in production (Render / Hostinger / Docker) real environment variables are used and win.
 * Placeholder values such as {@code your_groq_api_key_here} are treated as "not configured".
 */
public record Settings(
        String groqApiKey,
        String groqModelId,
        String groqBaseUrl,
        double llmTemperature,
        int llmMaxTokens,
        int llmMaxRetries,
        int llmTimeoutSeconds,
        String aviationKey,
        String aviationBaseUrl,
        String openWeatherKey,
        String openWeatherBaseUrl,
        String defaultOriginIata,
        String tavilyKey,
        String tavilyBaseUrl,
        String databaseUrl,
        boolean tracingRequested,
        String langsmithEndpoint,
        String langsmithKey,
        String langsmithProject,
        String appApiKey,
        List<String> allowedOrigins,
        int rateLimitPerMinute,
        int maxRevisions) {

    public static Settings load() {
        return load(key -> {
            String v = System.getenv(key);
            return v != null ? v : System.getProperty(key);
        });
    }

    public static Settings load(Function<String, String> src) {
        return new Settings(
                clean(src.apply("GROQ_API_KEY")),
                first(src, "openai/gpt-oss-20b", "GROQ_MODEL_ID"),
                first(src, "https://api.groq.com/openai/v1", "GROQ_BASE_URL"),
                number(src.apply("LLM_TEMPERATURE"), 0.3),
                (int) number(src.apply("LLM_MAX_TOKENS"), 3000),
                (int) number(src.apply("LLM_MAX_RETRIES"), 4),
                (int) number(src.apply("LLM_TIMEOUT_SECONDS"), 90),
                clean(firstRaw(src, "AVIATIONSTACK_API_KEY", "AVIATION_STACK_API_KEY")),
                first(src, "http://api.aviationstack.com/v1", "AVIATIONSTACK_BASE_URL"),
                clean(src.apply("OPENWEATHER_API_KEY")),
                first(src, "https://api.openweathermap.org/data/2.5", "OPENWEATHER_BASE_URL"),
                first(src, "DEL", "DEFAULT_ORIGIN_IATA").toUpperCase(),
                clean(src.apply("TAVILY_API_KEY")),
                first(src, "https://api.tavily.com", "TAVILY_BASE_URL"),
                clean(src.apply("DATABASE_URL")),
                "true".equalsIgnoreCase(Texts.orElse(clean(src.apply("LANGSMITH_TRACING")), "false"))
                        || "true".equalsIgnoreCase(Texts.orElse(clean(src.apply("LANGCHAIN_TRACING_V2")), "false")),
                first(src, "https://api.smith.langchain.com", "LANGSMITH_ENDPOINT"),
                clean(src.apply("LANGSMITH_API_KEY")),
                first(src, "TripTourWorld", "LANGSMITH_PROJECT"),
                clean(src.apply("APP_API_KEY")),
                csv(src.apply("ALLOWED_ORIGINS")),
                (int) number(src.apply("RATE_LIMIT_PER_MINUTE"), 30),
                (int) number(src.apply("MAX_REVISIONS"), 2));
    }

    // ------------------------------------------------------------------ derived flags

    public boolean llmConfigured() {
        return !Texts.blank(groqApiKey);
    }

    public boolean databaseConfigured() {
        return !Texts.blank(databaseUrl);
    }

    public boolean tracingEnabled() {
        return tracingRequested && !Texts.blank(langsmithKey);
    }

    public boolean apiKeyRequired() {
        return !Texts.blank(appApiKey);
    }

    // ------------------------------------------------------------------ parsing helpers

    /** Returns "" for blank values and for template placeholders like "your_x_here". */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        if (v.isEmpty() || v.toLowerCase().startsWith("your_") || v.toLowerCase().endsWith("_here")) {
            return "";
        }
        return v;
    }

    private static String firstRaw(Function<String, String> src, String... names) {
        for (String n : names) {
            String v = src.apply(n);
            if (!Texts.blank(clean(v))) {
                return v;
            }
        }
        return "";
    }

    private static String first(Function<String, String> src, String fallback, String name) {
        String v = clean(src.apply(name));
        return v.isEmpty() ? fallback : v;
    }

    private static double number(String raw, double fallback) {
        try {
            return Texts.blank(raw) ? fallback : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> csv(String raw) {
        if (Texts.blank(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
