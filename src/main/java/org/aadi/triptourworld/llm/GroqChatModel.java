package org.aadi.triptourworld.llm;

import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.http.HttpJson;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.util.Json;
import org.aadi.triptourworld.util.Texts;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Groq adapter (OpenAI-compatible {@code /chat/completions}) with retry/backoff for the 429s that
 * Groq's free tier produces, optional LangSmith "llm" runs, and clean-up of reasoning tags.
 */
public final class GroqChatModel implements ChatModel {
    private static final Logger LOG = Logger.getLogger(GroqChatModel.class.getName());
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

    /** Sleep indirection so tests don't actually wait. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Settings cfg;
    private final HttpJson http;
    private final LangSmithTracer tracer;
    private final Sleeper sleeper;

    public GroqChatModel(Settings cfg, HttpJson http, LangSmithTracer tracer) {
        this(cfg, http, tracer, Thread::sleep);
    }

    public GroqChatModel(Settings cfg, HttpJson http, LangSmithTracer tracer, Sleeper sleeper) {
        this.cfg = cfg;
        this.http = http;
        this.tracer = tracer;
        this.sleeper = sleeper;
    }

    @Override
    public String name() {
        return "groq:" + cfg.groqModelId();
    }

    @Override
    public String chat(String system, String user) {
        if (!cfg.llmConfigured()) {
            throw new LlmException("GROQ_API_KEY is not configured. Add it to .env (local) or the host's environment.");
        }
        List<Object> messages = Json.arr(
                Json.obj("role", "system", "content", system),
                Json.obj("role", "user", "content", user));
        Map<String, Object> body = Json.obj(
                "model", cfg.groqModelId(),
                "messages", messages,
                "temperature", cfg.llmTemperature(),
                "max_completion_tokens", cfg.llmMaxTokens());
        if (cfg.groqModelId().contains("gpt-oss")) {
            body.put("reasoning_effort", "low"); // keeps latency and free-tier token usage down
        }

        try (LangSmithTracer.Span span = tracer.childOfCurrent("groq.chat", "llm", Json.obj("messages", messages))) {
            span.meta("invocation_params", Json.obj("model", cfg.groqModelId(), "temperature", cfg.llmTemperature()))
                    .meta("metadata", Json.obj("ls_provider", "groq", "ls_model_name", cfg.groqModelId()));
            try {
                String content = callWithRetry(Json.write(body), span);
                span.output("choices", Json.arr(Json.obj("message", Json.obj("role", "assistant", "content", content))));
                return content;
            } catch (RuntimeException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    private String callWithRetry(String payload, LangSmithTracer.Span span) {
        String url = cfg.groqBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        Map<String, String> headers = Map.of("Authorization", "Bearer " + cfg.groqApiKey());
        Duration timeout = Duration.ofSeconds(cfg.llmTimeoutSeconds());
        int attempts = Math.max(1, cfg.llmMaxRetries());
        String lastProblem = "unknown error";

        for (int attempt = 1; attempt <= attempts; attempt++) {
            long waitMs = 1500L * attempt;
            try {
                HttpJson.Response r = http.postJson(url, headers, payload, timeout);
                if (r.ok()) {
                    return parseContent(r.body(), span);
                }
                lastProblem = "HTTP " + r.status() + " " + Texts.truncate(extractError(r.body()), 300);
                if (r.status() != 429 && r.status() < 500) {
                    throw new LlmException("Groq rejected the request: " + lastProblem); // 4xx: retrying won't help
                }
                waitMs = retryAfterMillis(r, waitMs);
            } catch (IOException e) {
                lastProblem = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (attempt < attempts) {
                LOG.warning("Groq call failed (" + lastProblem + "); retry " + attempt + "/" + (attempts - 1)
                        + " in " + waitMs + " ms");
                try {
                    sleeper.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Interrupted while waiting to retry Groq", ie);
                }
            }
        }
        throw new LlmException("Groq call failed after " + attempts + " attempts: " + lastProblem);
    }

    private String parseContent(String responseBody, LangSmithTracer.Span span) {
        Object parsed;
        try {
            parsed = Json.parse(responseBody);
        } catch (IllegalArgumentException e) {
            throw new LlmException("Groq returned a non-JSON response");
        }
        String content = Json.str(parsed, "choices", 0, "message", "content");
        content = THINK.matcher(content).replaceAll("").strip();
        if (content.isEmpty()) {
            String finish = Json.str(parsed, "choices", 0, "finish_reason");
            throw new LlmException("Groq returned an empty answer (finish_reason=" + finish
                    + "). Try raising LLM_MAX_TOKENS.");
        }
        Object usage = Json.at(parsed, "usage");
        if (usage != null) {
            span.output("usage_metadata", Json.obj(
                    "input_tokens", Json.at(usage, "prompt_tokens"),
                    "output_tokens", Json.at(usage, "completion_tokens"),
                    "total_tokens", Json.at(usage, "total_tokens")));
        }
        return content;
    }

    private static long retryAfterMillis(HttpJson.Response r, long fallback) {
        String h = r.header("retry-after");
        if (h != null) {
            try {
                return Math.min(20_000L, (long) (Double.parseDouble(h.trim()) * 1000) + 250);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static String extractError(String body) {
        try {
            String msg = Json.str(Json.parse(body), "error", "message");
            return msg.isEmpty() ? body : msg;
        } catch (RuntimeException e) {
            return body == null ? "" : body;
        }
    }
}
