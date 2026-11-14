package org.aadi.triptourworld.service;

import org.aadi.triptourworld.agents.Agents;
import org.aadi.triptourworld.agents.Routing;
import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.graph.TripGraphRunner;
import org.aadi.triptourworld.llm.LlmException;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.persistence.TripRecord;
import org.aadi.triptourworld.persistence.TripRepository;
import org.aadi.triptourworld.persistence.TripStatus;
import org.aadi.triptourworld.util.Json;
import org.aadi.triptourworld.util.Texts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Orchestrates trips: starts the graph asynchronously (virtual threads), tracks progress/status in the
 * repository, handles the human review, and recovers approvals after a restart.
 *
 * <p>Restart recovery: LangGraph4j's MemorySaver holds the live checkpoint of a paused thread. Hosts like
 * Render free tier sleep or redeploy, wiping memory. The state snapshot saved at every pause lets the
 * service rebuild the state and run the remaining nodes directly, so a pending approval is never lost.
 */
public final class TripService implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(TripService.class.getName());
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final int MAX_CONCURRENT_RUNS = 4;
    private static final long LIVE_TTL_MS = 6 * 60 * 60 * 1000L;

    private final Settings cfg;
    private final TripRepository repo;
    private final TripGraphRunner runner;
    private final Agents agents;
    private final LangSmithTracer tracer;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_RUNS);
    private final Map<String, Long> live = new ConcurrentHashMap<>(); // threads paused with an in-memory checkpoint

    public TripService(Settings cfg, TripRepository repo, TripGraphRunner runner, Agents agents, LangSmithTracer tracer) {
        this.cfg = cfg;
        this.repo = repo;
        this.runner = runner;
        this.agents = agents;
        this.tracer = tracer;
    }

    // ------------------------------------------------------------------ public API

    public TripRecord submit(String message) {
        String q = message == null ? "" : message.strip();
        if (q.isEmpty()) {
            throw new ApiException(400, "Message cannot be empty.");
        }
        if (!cfg.llmConfigured()) {
            throw new ApiException(503, "The AI model is not configured (GROQ_API_KEY missing on the server).");
        }
        sweep();
        acquire();
        String id = "trip_" + UUID.randomUUID().toString().replace("-", "");
        try {
            repo.create(id, q);
            exec.submit(() -> runStart(id, q));
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
        return repo.find(id).orElseThrow();
    }

    public TripRecord get(String id) {
        requireValid(id);
        return repo.find(id).orElseThrow(() -> new ApiException(404, "Trip not found."));
    }

    public List<TripRecord> recent(int limit) {
        return repo.recent(Math.max(1, Math.min(limit, 50)));
    }

    public TripRecord review(String id, boolean approved, String feedback) {
        TripRecord t = get(id);
        String fb = feedback == null ? "" : feedback.strip();
        if (t.status() != TripStatus.AWAITING_APPROVAL) {
            throw new ApiException(409, "This trip is not waiting for approval (status: " + t.status() + ").");
        }
        if (!approved && fb.isEmpty()) {
            throw new ApiException(400, "Please describe the changes you want when requesting a revision.");
        }
        acquire();
        String feedbackFinal = Texts.truncate(fb, 2000);
        try {
            repo.addReview(id, approved, feedbackFinal);
            repo.updateStatus(id, TripStatus.RUNNING, "");
            exec.submit(() -> runResume(id, approved, feedbackFinal));
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
        return repo.find(id).orElseThrow();
    }

    @Override
    public void close() {
        exec.shutdown();
    }

    // ------------------------------------------------------------------ execution

    private void runStart(String id, String message) {
        LangSmithTracer.Span root = tracer.root("TripTourWorld.plan", id,
                Json.obj("user_query", Texts.truncate(message, 1000)));
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(TravelState.THREAD_ID, id);
            input.put(TravelState.USER_QUERY, message);
            input.put(TravelState.APPROVED, false);
            input.put(TravelState.HUMAN_FEEDBACK, "");
            input.put(TravelState.REVISION_COUNT, 0);
            input.put(TravelState.LLM_CALLS, 0);
            input.put(TravelState.STAGE, TravelState.STAGE_RUNNING);
            input.putAll(traceKeys(root));
            TravelState last = runner.start(id, input, node -> repo.appendProgress(id, node));
            settle(id, last, root, true);
        } catch (Throwable t) {
            fail(id, t, root);
        } finally {
            root.close();
            permits.release();
        }
    }

    private void runResume(String id, boolean approved, String feedback) {
        LangSmithTracer.Span root = tracer.root("TripTourWorld.review", id,
                Json.obj("approved", approved, "feedback", Texts.truncate(feedback, 500)));
        try {
            Map<String, Object> trace = traceKeys(root);
            TravelState last;
            boolean checkpointed = live.containsKey(id);
            if (checkpointed) {
                last = runner.resume(id, approved, feedback, trace, node -> repo.appendProgress(id, node));
            } else {
                last = recover(id, approved, feedback, trace);
            }
            settle(id, last, root, checkpointed);
        } catch (Throwable t) {
            fail(id, t, root);
        } finally {
            root.close();
            permits.release();
        }
    }

    /** Rebuilds the paused state from the database and runs the remaining nodes (approval -> final | itinerary). */
    TravelState recover(String id, boolean approved, String feedback, Map<String, Object> trace) throws Exception {
        TripRecord rec = repo.find(id).orElseThrow(() -> new ApiException(404, "Trip not found."));
        if (rec.state().isEmpty()) {
            throw new ApiException(410, "This session expired. Please submit the request again.");
        }
        LOG.info("Recovering " + id + " from the saved snapshot (no live checkpoint)");
        Map<String, Object> patch = new LinkedHashMap<>(trace);
        patch.put(TravelState.APPROVED, approved);
        patch.put(TravelState.HUMAN_FEEDBACK, feedback);
        TravelState s = new TravelState(new LinkedHashMap<>(rec.state())).with(patch);

        s = s.with(agents.approval.apply(s));
        repo.appendProgress(id, Routing.APPROVAL);
        String next = Routing.afterReview(approved, s.integer(TravelState.REVISION_COUNT), cfg.maxRevisions());
        if (Routing.FINAL.equals(next)) {
            s = s.with(agents.finalAgent.apply(s));
            repo.appendProgress(id, Routing.FINAL);
        } else {
            s = s.with(agents.itinerary.apply(s));
            repo.appendProgress(id, Routing.ITINERARY);
        }
        return s;
    }

    /** @param checkpointed true when the graph holds a live in-memory checkpoint for this thread (false after recovery). */
    private void settle(String id, TravelState last, LangSmithTracer.Span root, boolean checkpointed) {
        repo.saveState(id, last.snapshot());
        String stage = last.str(TravelState.STAGE);
        TripStatus status;
        if (TravelState.STAGE_BLOCKED.equals(stage)) {
            status = TripStatus.BLOCKED;
        } else if (TravelState.STAGE_COMPLETED.equals(stage) && !last.str(TravelState.FINAL_RESPONSE).isBlank()) {
            status = TripStatus.COMPLETED;
        } else {
            status = TripStatus.AWAITING_APPROVAL;
        }
        if (status == TripStatus.AWAITING_APPROVAL && checkpointed) {
            live.put(id, System.currentTimeMillis());
        } else if (status == TripStatus.AWAITING_APPROVAL) {
            live.remove(id); // paused via the saved snapshot only; the next review recovers from it again
        } else {
            live.remove(id);
            runner.release(id);
        }
        repo.updateStatus(id, status, "");
        root.output("status", status.name()).output("llm_calls", last.integer(TravelState.LLM_CALLS));
    }

    private void fail(String id, Throwable t, LangSmithTracer.Span root) {
        LOG.log(Level.SEVERE, "Trip " + id + " failed", t);
        root.fail(t);
        live.remove(id);
        runner.release(id);
        // LangGraph4j may wrap node exceptions; look through the cause chain for errors that are safe to show.
        Throwable known = t;
        for (int i = 0; i < 8 && known != null && !(known instanceof LlmException || known instanceof ApiException); i++) {
            known = known.getCause();
        }
        String msg = known == null ? "Trip planning failed unexpectedly. Please try again." : known.getMessage();
        repo.updateStatus(id, TripStatus.FAILED, Texts.truncate(msg, 500));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> traceKeys(LangSmithTracer.Span root) {
        LangSmithTracer.Ref r = root.ref();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(TravelState.TRACE_ID, r.traceId());
        m.put(TravelState.ROOT_RUN_ID, r.runId());
        m.put(TravelState.ROOT_DOTTED_ORDER, r.dottedOrder());
        return m;
    }

    private void acquire() {
        if (!permits.tryAcquire()) {
            throw new ApiException(429, "The planner is busy with other trips. Please retry in a moment.");
        }
    }

    private void sweep() {
        long cutoff = System.currentTimeMillis() - LIVE_TTL_MS;
        live.entrySet().removeIf(e -> {
            if (e.getValue() < cutoff) {
                runner.release(e.getKey());
                return true;
            }
            return false;
        });
    }

    private static void requireValid(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new ApiException(400, "Invalid trip id.");
        }
    }
}
