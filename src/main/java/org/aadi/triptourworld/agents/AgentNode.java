package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import java.util.Map;

/** One step of the workflow: reads the shared state, returns the keys it wants to update. */
public interface AgentNode {

    /** Graph node id (also used in progress events and LangSmith run names). */
    String name();

    Map<String, Object> apply(TravelState state) throws Exception;
}
