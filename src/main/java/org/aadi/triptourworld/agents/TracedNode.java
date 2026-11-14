package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.util.Json;
import org.aadi.triptourworld.util.Texts;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decorator that records each node as a LangSmith run (parent = the trip's root run, found in state).
 * Any LLM / tool call the node makes becomes a child of this run via the tracer's thread-local.
 */
public final class TracedNode implements AgentNode {
    private final AgentNode inner;
    private final LangSmithTracer tracer;

    public TracedNode(AgentNode inner, LangSmithTracer tracer) {
        this.inner = inner;
        this.tracer = tracer;
    }

    @Override
    public String name() {
        return inner.name();
    }

    @Override
    public Map<String, Object> apply(TravelState state) throws Exception {
        LangSmithTracer.Ref parent = new LangSmithTracer.Ref(
                state.str(TravelState.TRACE_ID), state.str(TravelState.ROOT_RUN_ID),
                state.str(TravelState.ROOT_DOTTED_ORDER), state.str(TravelState.THREAD_ID));
        try (LangSmithTracer.Span span = tracer.child(parent, inner.name(), "chain",
                Json.obj("user_query", Texts.truncate(state.str(TravelState.USER_QUERY), 500)))) {
            try {
                Map<String, Object> updates = inner.apply(state);
                span.output("updates", summarize(updates));
                return updates;
            } catch (Exception | Error e) {
                span.fail(e);
                throw e;
            }
        }
    }

    private static Map<String, Object> summarize(Map<String, Object> updates) {
        Map<String, Object> out = new LinkedHashMap<>();
        updates.forEach((k, v) -> out.put(k, v instanceof CharSequence s ? Texts.truncate(s.toString(), 800) : v));
        return out;
    }
}
