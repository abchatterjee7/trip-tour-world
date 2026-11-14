package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.util.Texts;
import java.util.Map;

/** Itinerary planner: builds the draft that a human reviews; re-runs with feedback when changes are requested. */
public final class ItineraryNode extends BaseAgent {
    private static final String DRAFT_PROMPT = """
            Create a complete travel itinerary.

            {{request}}

            Trip constraints: {{constraints}}

            Flight results:
            {{flights}}

            Hotel results:
            {{hotels}}

            Weather results:
            {{weather}}

            Budget results:
            {{budget}}

            Make the itinerary practical, budget-aware, and easy to follow.
            Create a clear draft that is ready for human review.
            """;

    private static final String REVISE_PROMPT = """
            Revise the travel itinerary draft below to satisfy the reviewer's feedback.
            Keep everything the reviewer did not criticise. Return the full updated draft.

            {{request}}

            Trip constraints: {{constraints}}

            Reviewer feedback (apply it carefully):
            {{feedback}}

            Previous draft:
            {{draft}}

            Supporting data (flights / hotels / weather / budget):
            {{flights}}
            {{hotels}}
            {{weather}}
            {{budget}}
            """;

    public ItineraryNode(ChatModel llm) {
        super(llm);
    }

    @Override
    public String name() {
        return Routing.ITINERARY;
    }

    @Override
    public Map<String, Object> apply(TravelState s) {
        String feedback = s.str(TravelState.HUMAN_FEEDBACK).strip();
        String previous = s.str(TravelState.ITINERARY);
        boolean revising = !feedback.isEmpty() && !previous.isEmpty();

        String[] common = {
                "request", userBlock(s.str(TravelState.USER_QUERY)),
                "constraints", constraintText(s),
                "flights", Texts.truncate(s.str(TravelState.FLIGHT_RESULTS), 1500),
                "hotels", Texts.truncate(s.str(TravelState.HOTEL_RESULTS), 1500),
                "weather", Texts.truncate(s.str(TravelState.WEATHER_RESULTS), 800),
                "budget", Texts.truncate(s.str(TravelState.BUDGET_RESULTS), 1500)};

        String text;
        if (revising) {
            String[] kv = java.util.Arrays.copyOf(common, common.length + 4);
            kv[common.length] = "feedback";
            kv[common.length + 1] = Texts.truncate(feedback, 1500);
            kv[common.length + 2] = "draft";
            kv[common.length + 3] = Texts.truncate(previous, 5000);
            text = llm.chat("You are an expert travel planner revising a draft.", Texts.render(REVISE_PROMPT, kv));
        } else {
            text = llm.chat("You are an expert travel planner.", Texts.render(DRAFT_PROMPT, common));
        }
        return up(
                TravelState.ITINERARY, text,
                TravelState.APPROVAL_REQUEST,
                "Please review the draft itinerary. Approve it to create the final polished plan, or request changes.",
                TravelState.APPROVED, false,
                TravelState.HUMAN_FEEDBACK, "",
                TravelState.STAGE, TravelState.STAGE_DRAFT_READY,
                TravelState.LLM_CALLS, calls(s) + 1,
                TravelState.EVENTS, revising ? "Itinerary agent: draft revised from feedback" : "Itinerary agent: draft ready for review");
    }
}
