package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.guardrails.OutputGuardrail;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.util.Texts;
import java.util.Map;

/** Final response agent: polishes the approved draft into the user-facing plan, then runs the output guardrail. */
public final class FinalNode extends BaseAgent {
    private static final String PROMPT = """
            Generate the final travel response for the user.

            Human review:
            {{review}}

            {{request}}

            Supervisor constraints: {{constraints}}

            Flights:
            {{flights}}

            Hotels:
            {{hotels}}

            Weather:
            {{weather}}

            Budget analysis:
            {{budget}}

            Draft itinerary:
            {{draft}}

            Format the final answer in Markdown using exactly these sections as "## " headings:
            1. Trip Summary
            2. Flight Information
            3. Hotel Suggestions
            4. Weather Information
            5. Day-by-Day Itinerary
            6. Estimated Budget
            7. Final Recommendations

            Important:
            - Be clear and practical.
            - Mention that live flight APIs may not provide ticket prices when pricing is unavailable.
            - Include weather-based travel advice.
            - Keep the response useful for real travel planning.
            - Incorporate the human feedback when revision was requested.
            - Never include API keys, credentials or personal data.
            """;

    private final OutputGuardrail output;

    public FinalNode(ChatModel llm, OutputGuardrail output) {
        super(llm);
        this.output = output;
    }

    @Override
    public String name() {
        return Routing.FINAL;
    }

    @Override
    public Map<String, Object> apply(TravelState s) {
        String review = s.bool(TravelState.APPROVED)
                ? "The user approved the draft. Preserve its decisions while polishing it."
                : "The user requested a revision. Apply this feedback carefully:\n"
                        + Texts.orElse(s.str(TravelState.HUMAN_FEEDBACK), "Improve the draft before finalizing it.");
        String text = llm.chat("You are a professional AI travel booking assistant.", Texts.render(PROMPT,
                "review", review,
                "request", userBlock(s.str(TravelState.USER_QUERY)),
                "constraints", constraintText(s),
                "flights", Texts.truncate(s.str(TravelState.FLIGHT_RESULTS), 1500),
                "hotels", Texts.truncate(s.str(TravelState.HOTEL_RESULTS), 1500),
                "weather", Texts.truncate(s.str(TravelState.WEATHER_RESULTS), 800),
                "budget", Texts.truncate(s.str(TravelState.BUDGET_RESULTS), 1200),
                "draft", Texts.truncate(s.str(TravelState.ITINERARY), 4500)));
        return up(
                TravelState.FINAL_RESPONSE, output.sanitize(text),
                TravelState.STAGE, TravelState.STAGE_COMPLETED,
                TravelState.LLM_CALLS, calls(s) + 1,
                TravelState.EVENTS, "Final agent: plan generated");
    }
}
