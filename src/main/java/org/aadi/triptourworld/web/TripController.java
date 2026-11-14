package org.aadi.triptourworld.web;

import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.eval.EvalService;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.persistence.TripRepository;
import org.aadi.triptourworld.service.ApiException;
import org.aadi.triptourworld.service.TripService;
import org.aadi.triptourworld.util.Json;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TripController {
    private final TripService trips;
    private final EvalService evals;
    private final Settings settings;
    private final TripRepository repo;
    private final LangSmithTracer tracer;
    private final ChatModel llm;

    public TripController(TripService trips, EvalService evals, Settings settings, TripRepository repo,
                          LangSmithTracer tracer, ChatModel llm) {
        this.trips = trips;
        this.evals = evals;
        this.settings = settings;
        this.repo = repo;
        this.tracer = tracer;
        this.llm = llm;
    }

    /** Starts a trip-planning run. Returns 202 immediately; poll GET /api/trips/{id} for progress. */
    @PostMapping("/trips")
    public ResponseEntity<TripView> create(@Valid @RequestBody TripRequest request) {
        return ResponseEntity.accepted().body(TripView.of(trips.submit(request.message())));
    }

    @GetMapping("/trips/{id}")
    public TripView get(@PathVariable("id") String id) {
        return TripView.of(trips.get(id));
    }

    @GetMapping("/trips")
    public List<TripSummary> list(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        return trips.recent(limit).stream().map(TripSummary::of).toList();
    }

    /** Human-in-the-loop decision: approve, or request changes with feedback. */
    @PostMapping("/trips/{id}/review")
    public ResponseEntity<TripView> review(@PathVariable("id") String id, @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.accepted().body(TripView.of(trips.review(id, request.approved(), request.feedback())));
    }

    @PostMapping("/evals/run")
    public Map<String, Object> runEvals(@RequestParam(name = "suite", defaultValue = "all") String suite) {
        try {
            return evals.run(suite);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    @GetMapping("/evals/latest")
    public Object latestEval() {
        return evals.latestJson().<Object>map(Json::parse)
                .orElseThrow(() -> new ApiException(404, "No eval has been run yet. POST /api/evals/run first."));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("app", "TripTourWorld");
        out.put("llm", settings.llmConfigured() ? llm.name() : "not configured");
        out.put("database", repo.kind());
        out.put("tracing", tracer.enabled());
        out.put("apiKeyRequired", settings.apiKeyRequired());
        out.put("liveTools", Map.of(
                "flights", !settings.aviationKey().isBlank(),
                "weather", !settings.openWeatherKey().isBlank(),
                "hotelSearch", !settings.tavilyKey().isBlank()));
        out.put("features", List.of("supervisor_agent", "input_guardrail", "output_guardrail",
                "human_in_the_loop", "langsmith_tracing", "evals", "rate_limiting"));
        return out;
    }
}
