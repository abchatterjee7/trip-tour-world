package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.tools.FlightTool;
import org.aadi.triptourworld.tools.ToolException;
import org.aadi.triptourworld.util.Texts;
import java.util.Map;

/** Flight specialist: AviationStack data (when available) summarised into booking guidance by the LLM. */
public final class FlightNode extends BaseAgent {
    private static final String PROMPT = """
            You are a travel flight expert.

            {{request}}

            Trip constraints (JSON): {{constraints}}
            Route: {{origin}} -> {{destination}}

            Live flight data:
            {{live}}

            Generate:
            1. Likely departure airport
            2. Likely arrival airport
            3. Airlines serving this route
            4. Typical flight duration
            5. Estimated airfare range (label it clearly as an estimate)
            6. Peak season pricing warning
            7. Booking advice

            Return concise travel guidance.
            """;

    private final FlightTool tool;
    private final Settings cfg;

    public FlightNode(ChatModel llm, FlightTool tool, Settings cfg) {
        super(llm);
        this.tool = tool;
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return Routing.FLIGHT;
    }

    @Override
    public Map<String, Object> apply(TravelState s) {
        Map<String, Object> c = s.map(TravelState.CONSTRAINTS);
        String origin = Constraints.validIata(String.valueOf(c.getOrDefault("origin_iata", "")));
        if (origin.isEmpty()) {
            origin = cfg.defaultOriginIata();
        }
        String dest = Constraints.validIata(String.valueOf(c.getOrDefault("destination_iata", "")));

        String live;
        if (dest.isEmpty()) {
            live = "Destination airport could not be determined; give general guidance and say so.";
        } else {
            try {
                live = tool.lookup(origin, dest);
            } catch (ToolException e) {
                live = "Live flight data unavailable (" + e.getMessage() + "). Provide general, clearly-labelled guidance.";
            }
        }
        String text = llm.chat("You are an expert travel flight planner.", Texts.render(PROMPT,
                "request", userBlock(s.str(TravelState.USER_QUERY)),
                "constraints", constraintText(s),
                "origin", origin,
                "destination", dest.isEmpty() ? String.valueOf(c.getOrDefault("destination", "?")) : dest,
                "live", Texts.truncate(live, 2000)));
        return up(TravelState.FLIGHT_RESULTS, text,
                TravelState.LLM_CALLS, calls(s) + 1,
                TravelState.EVENTS, "Flight agent: " + origin + " -> " + (dest.isEmpty() ? "?" : dest));
    }
}
