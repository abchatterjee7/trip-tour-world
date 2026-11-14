package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.util.Texts;
import java.util.Map;

/** Budget analyst: is the trip realistic, where are the risks, how to save. */
public final class BudgetNode extends BaseAgent {
    private static final String PROMPT = """
            Analyze whether this trip is realistic for the user's budget.

            {{request}}

            Trip constraints: {{constraints}}

            Flight results:
            {{flights}}

            Hotel results:
            {{hotels}}

            Weather results:
            {{weather}}

            Return:
            1. Estimated cost categories
            2. Budget risk areas
            3. Money-saving suggestions
            4. Overall feasibility

            If exact live prices are unavailable, clearly label estimates as approximate.
            """;

    public BudgetNode(ChatModel llm) {
        super(llm);
    }

    @Override
    public String name() {
        return Routing.BUDGET;
    }

    @Override
    public Map<String, Object> apply(TravelState s) {
        String text = llm.chat("You are a practical travel budget analyst.", Texts.render(PROMPT,
                "request", userBlock(s.str(TravelState.USER_QUERY)),
                "constraints", constraintText(s),
                "flights", Texts.truncate(s.str(TravelState.FLIGHT_RESULTS), 1500),
                "hotels", Texts.truncate(s.str(TravelState.HOTEL_RESULTS), 1500),
                "weather", Texts.truncate(s.str(TravelState.WEATHER_RESULTS), 800)));
        return up(TravelState.BUDGET_RESULTS, text,
                TravelState.LLM_CALLS, calls(s) + 1,
                TravelState.EVENTS, "Budget agent: assessment ready");
    }
}
