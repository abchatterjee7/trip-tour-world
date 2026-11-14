package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.graph.TravelState;
import java.util.Map;

/**
 * Human-in-the-loop checkpoint. The graph is compiled with {@code interruptBefore("human_approval")}, so execution
 * pauses here until a reviewer's decision is written into the state (approved / feedback) and the run is resumed.
 */
public final class ApprovalNode implements AgentNode {
    private final Settings cfg;

    public ApprovalNode(Settings cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return Routing.APPROVAL;
    }

    @Override
    public Map<String, Object> apply(TravelState s) {
        boolean approved = s.bool(TravelState.APPROVED);
        int revisions = s.integer(TravelState.REVISION_COUNT) + (approved ? 0 : 1);
        String next = Routing.afterReview(approved, revisions, cfg.maxRevisions());
        String decision = approved ? "APPROVED" : (Routing.ITINERARY.equals(next) ? "REVISE" : "REVISION_LIMIT");
        return Map.of(
                TravelState.DECISION, decision,
                TravelState.REVISION_COUNT, revisions,
                TravelState.EVENTS, "Human review: " + decision);
    }
}
