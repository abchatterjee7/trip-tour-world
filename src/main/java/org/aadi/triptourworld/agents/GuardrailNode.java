package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.guardrails.InputGuardrail;
import java.util.Map;

/** Node 1: input guardrail (relevance, safety, prompt-injection). */
public final class GuardrailNode implements AgentNode {
    private final InputGuardrail guardrail;

    public GuardrailNode(InputGuardrail guardrail) {
        this.guardrail = guardrail;
    }

    @Override
    public String name() {
        return "input_guardrail";
    }

    @Override
    public Map<String, Object> apply(TravelState s) {
        InputGuardrail.Result r = guardrail.check(s.str(TravelState.USER_QUERY));
        int calls = s.integer(TravelState.LLM_CALLS) + (r.usedLlm() ? 1 : 0);
        if (r.allowed()) {
            return Map.of(
                    TravelState.GUARDRAIL_ALLOWED, true,
                    TravelState.GUARDRAIL_REASON, "",
                    TravelState.GUARDRAIL_CATEGORY, r.category().name(),
                    TravelState.LLM_CALLS, calls,
                    TravelState.EVENTS, "Guardrail: request allowed");
        }
        return Map.of(
                TravelState.GUARDRAIL_ALLOWED, false,
                TravelState.GUARDRAIL_REASON, r.reason(),
                TravelState.GUARDRAIL_CATEGORY, r.category().name(),
                TravelState.LLM_CALLS, calls,
                TravelState.EVENTS, "Guardrail: blocked (" + r.category() + ")");
    }
}
