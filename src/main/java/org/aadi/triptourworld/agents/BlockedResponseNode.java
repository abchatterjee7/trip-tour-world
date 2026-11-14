package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import java.util.Map;

/** Terminal node for blocked requests: returns the guardrail's explanation instead of running agents. */
public final class BlockedResponseNode implements AgentNode {
    @Override
    public String name() {
        return "guardrail_blocked";
    }

    @Override
    public Map<String, Object> apply(TravelState s) {
        String reason = s.str(TravelState.GUARDRAIL_REASON);
        if (reason.isBlank()) {
            reason = "This request was blocked by the travel input guardrail.";
        }
        return Map.of(
                TravelState.FINAL_RESPONSE, reason,
                TravelState.STAGE, TravelState.STAGE_BLOCKED,
                TravelState.EVENTS, "Blocked response returned");
    }
}
