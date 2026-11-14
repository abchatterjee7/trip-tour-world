package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.tools.ToolException;
import org.aadi.triptourworld.tools.WebSearchTool;
import org.aadi.triptourworld.util.Texts;
import java.util.Map;

/** Hotel specialist: Tavily web search; degrades to explicit "non-live advice" like the original. */
public final class HotelNode implements AgentNode {
    private static final String FALLBACK = "Live hotel search is temporarily unavailable. Provide general accommodation "
            + "and neighborhood guidance based on the destination and clearly label it as non-live advice.";

    private final WebSearchTool search;

    public HotelNode(WebSearchTool search) {
        this.search = search;
    }

    @Override
    public String name() {
        return Routing.HOTEL;
    }

    @Override
    public Map<String, Object> apply(TravelState s) {
        Map<String, Object> c = s.map(TravelState.CONSTRAINTS);
        String dest = String.valueOf(c.getOrDefault("destination", "")).strip();
        String budget = String.valueOf(c.getOrDefault("budget", "")).strip();
        String query = dest.isEmpty() ? "Best hotels for " + s.str(TravelState.USER_QUERY)
                : "Best hotels and neighborhoods to stay in " + dest + (budget.isEmpty() ? "" : " budget " + budget);
        String result;
        String event;
        try {
            result = Texts.truncate(search.search(Texts.truncate(query, 300), 5), 2500);
            event = "Hotel agent: live search ok";
        } catch (ToolException e) {
            result = FALLBACK;
            event = "Hotel agent: fallback (" + e.getMessage() + ")";
        }
        return Map.of(TravelState.HOTEL_RESULTS, result, TravelState.EVENTS, event);
    }
}
