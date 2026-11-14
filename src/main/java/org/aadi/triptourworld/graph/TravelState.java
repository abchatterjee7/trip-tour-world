package org.aadi.triptourworld.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

/**
 * Shared state passed between all agents (the Java counterpart of TripBabu's {@code TravelState}).
 *
 * <p>Every value is a plain JSON-friendly type (String, Boolean, Integer, List, Map) so a snapshot can be
 * stored in Postgres and re-hydrated after a restart. {@code events} uses an appender channel
 * (reducer) so each node can add a log line without overwriting the others.
 */
public class TravelState extends AgentState {

    // -------- state keys
    public static final String THREAD_ID = "thread_id";
    public static final String USER_QUERY = "user_query";
    public static final String GUARDRAIL_ALLOWED = "guardrail_allowed";
    public static final String GUARDRAIL_REASON = "guardrail_reason";
    public static final String GUARDRAIL_CATEGORY = "guardrail_category";
    public static final String SELECTED_AGENTS = "selected_agents";
    public static final String CONSTRAINTS = "trip_constraints";
    public static final String SUPERVISOR_REASONING = "supervisor_reasoning";
    public static final String FLIGHT_RESULTS = "flight_results";
    public static final String HOTEL_RESULTS = "hotel_results";
    public static final String WEATHER_RESULTS = "weather_results";
    public static final String BUDGET_RESULTS = "budget_results";
    public static final String ITINERARY = "itinerary";
    public static final String APPROVAL_REQUEST = "approval_request";
    public static final String APPROVED = "approved";
    public static final String HUMAN_FEEDBACK = "human_feedback";
    public static final String DECISION = "decision";
    public static final String REVISION_COUNT = "revision_count";
    public static final String FINAL_RESPONSE = "final_response";
    public static final String LLM_CALLS = "llm_calls";
    public static final String EVENTS = "events";
    public static final String STAGE = "stage";
    public static final String TRACE_ID = "trace_id";
    public static final String ROOT_RUN_ID = "root_run_id";
    public static final String ROOT_DOTTED_ORDER = "root_dotted_order";

    // -------- stage values
    public static final String STAGE_RUNNING = "RUNNING";
    public static final String STAGE_BLOCKED = "BLOCKED";
    public static final String STAGE_DRAFT_READY = "DRAFT_READY";
    public static final String STAGE_COMPLETED = "COMPLETED";

    public static final Map<String, Channel<?>> SCHEMA = Map.of(EVENTS, Channels.appender(ArrayList::new));

    public TravelState(Map<String, Object> initData) {
        super(initData);
    }

    public String str(String key) {
        return this.<Object>value(key).map(String::valueOf).orElse("");
    }

    public boolean bool(String key) {
        return this.<Object>value(key).map(v -> v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v))).orElse(false);
    }

    public int integer(String key) {
        return this.<Object>value(key).map(v -> v instanceof Number n ? n.intValue() : parseInt(String.valueOf(v))).orElse(0);
    }

    public List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        this.<Object>value(key).ifPresent(v -> {
            if (v instanceof Iterable<?> it) {
                for (Object o : it) {
                    out.add(String.valueOf(o));
                }
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        return this.<Object>value(key).filter(v -> v instanceof Map).map(v -> (Map<String, Object>) v).orElse(new LinkedHashMap<>());
    }

    /** Copy of the raw data (what gets persisted as the trip snapshot). */
    public Map<String, Object> snapshot() {
        return new LinkedHashMap<>(data());
    }

    /** Applies node-style updates (appending to {@code events}) and returns a new state - used outside the graph. */
    public TravelState with(Map<String, Object> updates) {
        Map<String, Object> merged = new LinkedHashMap<>(data());
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            if (EVENTS.equals(e.getKey())) {
                List<Object> events = new ArrayList<>();
                this.<Object>value(EVENTS).ifPresent(v -> {
                    if (v instanceof Iterable<?> it) {
                        it.forEach(events::add);
                    }
                });
                if (e.getValue() instanceof Iterable<?> more) {
                    more.forEach(events::add);
                } else {
                    events.add(e.getValue());
                }
                merged.put(EVENTS, events);
            } else {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return new TravelState(merged);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
