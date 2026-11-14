package org.aadi.triptourworld.support;

import org.aadi.triptourworld.llm.ChatModel;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic stand-in for Groq: answers guardrail / supervisor prompts with JSON and everything else with text. */
public final class ScriptedChatModel implements ChatModel {
    public final AtomicInteger calls = new AtomicInteger();
    private final String supervisorJson;

    public ScriptedChatModel() {
        this("{\"selected_agents\":[\"flight_agent\",\"budget_agent\",\"itinerary_agent\"],"
                + "\"trip_constraints\":{\"destination\":\"Goa\",\"origin\":\"Hyderabad\",\"destination_iata\":\"GOI\","
                + "\"origin_iata\":\"HYD\",\"duration\":\"5 days\",\"budget\":\"40000 INR\",\"travel_style\":\"relaxed\","
                + "\"special_preferences\":[\"beaches\"]},\"reasoning\":\"Needs flights and a budget check.\"}");
    }

    public ScriptedChatModel(String supervisorJson) {
        this.supervisorJson = supervisorJson;
    }

    @Override
    public String chat(String system, String user) {
        calls.incrementAndGet();
        if (system.contains("input guardrail")) {
            return "{\"allowed\": true, \"reason\": \"\"}";
        }
        if (system.contains("route work")) {
            return supervisorJson;
        }
        if (system.contains("revising")) {
            return "REVISED DRAFT: " + user.length();
        }
        if (system.contains("professional AI travel booking assistant")) {
            StringBuilder sb = new StringBuilder();
            for (String s : new String[] {"Trip Summary", "Flight Information", "Hotel Suggestions", "Weather Information",
                    "Day-by-Day Itinerary", "Estimated Budget", "Final Recommendations"}) {
                sb.append("## ").append(s).append("\nDetails for travellers with realistic timings and costs, all labelled approximate.\n\n");
            }
            return sb.toString();
        }
        return "DRAFT: " + system.substring(0, Math.min(30, system.length()));
    }

    @Override
    public String name() {
        return "scripted";
    }
}
