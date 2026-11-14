package org.aadi.triptourworld.web;

import org.aadi.triptourworld.persistence.TripRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** API representation of a trip: status/progress plus every agent's output. */
public record TripView(
        String id,
        String status,
        String query,
        List<String> progress,
        List<String> selectedAgents,
        Map<String, Object> constraints,
        String supervisorReasoning,
        String guardrailCategory,
        String draftItinerary,
        String finalResponse,
        String flightResults,
        String hotelResults,
        String weatherResults,
        String budgetResults,
        int revisionCount,
        int llmCalls,
        List<String> events,
        String error,
        String createdAt,
        String updatedAt) {

    @SuppressWarnings("unchecked")
    public static TripView of(TripRecord r) {
        Map<String, Object> s = r.state();
        return new TripView(
                r.threadId(), r.status().name(), r.userQuery(), r.progress(),
                strings(s.get("selected_agents")),
                s.get("trip_constraints") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(),
                text(s, "supervisor_reasoning"), text(s, "guardrail_category"),
                text(s, "itinerary"), text(s, "final_response"),
                text(s, "flight_results"), text(s, "hotel_results"), text(s, "weather_results"), text(s, "budget_results"),
                num(s, "revision_count"), num(s, "llm_calls"), strings(s.get("events")),
                r.error() == null ? "" : r.error(), r.createdAt(), r.updatedAt());
    }

    private static String text(Map<String, Object> s, String k) {
        Object v = s.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static int num(Map<String, Object> s, String k) {
        return s.get(k) instanceof Number n ? n.intValue() : 0;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Iterable<?> it) {
            it.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }
}
