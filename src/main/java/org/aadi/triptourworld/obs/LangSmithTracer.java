package org.aadi.triptourworld.obs;

import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.http.HttpJson;
import org.aadi.triptourworld.util.Json;
import org.aadi.triptourworld.util.Texts;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LangSmith observability over its public REST API ({@code POST /runs/batch}).
 *
 * <p>LangSmith has no official Java SDK, so this class records the same run tree the Python SDK
 * would: one root chain run per request, one child chain run per graph node, and llm / tool runs
 * nested under the node that made the call. Runs from the same trip share
 * {@code metadata.thread_id}, which powers LangSmith's "Threads" view.
 *
 * <p>Tracing is strictly best-effort: it runs on a background thread, never blocks or fails a
 * trip, and is a no-op when {@code LANGSMITH_TRACING} is not "true" or the key is missing.
 */
public final class LangSmithTracer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LangSmithTracer.class.getName());
    private static final DateTimeFormatter ORDER_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final ThreadLocal<Span> CURRENT = new ThreadLocal<>();

    /** Identifies a run so children can attach to it (stored in graph state between nodes). */
    public record Ref(String traceId, String runId, String dottedOrder, String threadId) {
        public boolean valid() {
            return !Texts.blank(traceId) && !Texts.blank(runId) && !Texts.blank(dottedOrder);
        }
    }

    private final boolean enabled;
    private final String endpoint;
    private final String apiKey;
    private final String project;
    private final HttpJson http;
    private final ExecutorService sender;
    private final AtomicInteger failures = new AtomicInteger();

    public LangSmithTracer(Settings cfg, HttpJson http) {
        this.enabled = cfg.tracingEnabled();
        this.endpoint = cfg.langsmithEndpoint().replaceAll("/+$", "");
        this.apiKey = cfg.langsmithKey();
        this.project = cfg.langsmithProject();
        this.http = http;
        this.sender = enabled
                ? Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "langsmith-sender");
                    t.setDaemon(true);
                    return t;
                })
                : null;
        if (enabled) {
            LOG.info("LangSmith tracing ENABLED -> project '" + project + "'");
        } else {
            LOG.info("LangSmith tracing disabled (set LANGSMITH_TRACING=true and LANGSMITH_API_KEY to enable)");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public int failedSends() {
        return failures.get();
    }

    // ------------------------------------------------------------------ span factories

    /** Starts a new trace (root run). */
    public Span root(String name, String threadId, Map<String, Object> inputs) {
        return new Span(null, name, "chain", inputs, threadId, true);
    }

    /** Starts a run under an explicit parent (used by graph nodes, which get the parent from state). */
    public Span child(Ref parent, String name, String runType, Map<String, Object> inputs) {
        boolean ok = parent != null && parent.valid();
        return new Span(ok ? parent : null, name, runType, inputs, ok ? parent.threadId() : "", ok);
    }

    /** Starts a run under whatever span is active on the current thread (llm / tool calls). */
    public Span childOfCurrent(String name, String runType, Map<String, Object> inputs) {
        Span cur = CURRENT.get();
        return child(cur == null || !cur.recorded ? null : cur.ref(), name, runType, inputs);
    }

    // ------------------------------------------------------------------ Span

    public final class Span implements AutoCloseable {
        private final String id = UUID.randomUUID().toString();
        private final Instant start = Instant.now();
        private final String name;
        private final String runType;
        private final String traceId;
        private final String parentId;
        private final String dottedOrder;
        private final String threadId;
        private final boolean recorded;
        private final Span previous;
        private Map<String, Object> outputs = new LinkedHashMap<>();
        private Map<String, Object> extra = new LinkedHashMap<>();
        private String error;
        private boolean closed;

        private Span(Ref parent, String name, String runType, Map<String, Object> inputs, String threadId,
                     boolean recorded) {
            this.name = name;
            this.runType = runType;
            this.threadId = threadId == null ? "" : threadId;
            this.recorded = recorded && enabled;
            String order = ORDER_TS.format(start) + id;
            if (parent == null) {
                this.traceId = id;
                this.parentId = null;
                this.dottedOrder = order;
            } else {
                this.traceId = parent.traceId();
                this.parentId = parent.runId();
                this.dottedOrder = parent.dottedOrder() + "." + order;
            }
            this.previous = CURRENT.get();
            CURRENT.set(this);
            if (this.recorded) {
                post(inputs);
            }
        }

        public Ref ref() {
            return new Ref(traceId, id, dottedOrder, threadId);
        }

        public Span output(String key, Object value) {
            outputs.put(key, value);
            return this;
        }

        public Span outputs(Map<String, Object> map) {
            outputs.putAll(map);
            return this;
        }

        public Span meta(String key, Object value) {
            extra.put(key, value);
            return this;
        }

        public Span fail(Throwable t) {
            this.error = t.getClass().getSimpleName() + ": " + Texts.truncate(String.valueOf(t.getMessage()), 500);
            return this;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (CURRENT.get() == this) {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
            if (recorded) {
                patch();
            }
        }

        private Map<String, Object> identity() {
            Map<String, Object> run = Json.obj("id", id, "trace_id", traceId, "dotted_order", dottedOrder);
            if (parentId != null) {
                run.put("parent_run_id", parentId);
            }
            return run;
        }

        private void post(Map<String, Object> inputs) {
            Map<String, Object> metadata = Json.obj("app", "trip-tour-world");
            if (!threadId.isEmpty()) {
                metadata.put("thread_id", threadId);
            }
            Map<String, Object> run = identity();
            run.put("name", name);
            run.put("run_type", runType);
            run.put("start_time", start.toString());
            run.put("inputs", inputs == null ? Json.obj() : inputs);
            run.put("session_name", project);
            run.put("extra", Json.obj("metadata", metadata));
            run.put("tags", Json.arr("trip-tour-world", "langgraph4j"));
            submit(Json.obj("post", Json.arr(run)));
        }

        private void patch() {
            Map<String, Object> run = identity();
            run.put("end_time", Instant.now().toString());
            run.put("outputs", outputs);
            run.put("extra", extra);
            if (error != null) {
                run.put("error", error);
            }
            submit(Json.obj("patch", Json.arr(run)));
        }
    }

    // ------------------------------------------------------------------ transport

    private void submit(Map<String, Object> batch) {
        if (!enabled || sender == null || sender.isShutdown()) {
            return;
        }
        String body = Json.write(batch);
        sender.submit(() -> {
            try {
                HttpJson.Response r = http.postJson(endpoint + "/runs/batch",
                        Map.of("x-api-key", apiKey), body, Duration.ofSeconds(15));
                if (!r.ok()) {
                    noteFailure("HTTP " + r.status() + " " + Texts.truncate(r.body(), 200));
                }
            } catch (Exception e) {
                noteFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private void noteFailure(String why) {
        if (failures.incrementAndGet() == 1) {
            LOG.log(Level.WARNING, "LangSmith send failed (further failures are counted silently): {0}", why);
        }
    }

    /** Flushes pending sends (called on shutdown by Spring). */
    @Override
    public void close() {
        if (sender != null) {
            sender.shutdown();
            try {
                sender.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
