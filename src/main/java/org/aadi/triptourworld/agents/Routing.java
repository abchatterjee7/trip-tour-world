package org.aadi.triptourworld.agents;

import java.util.ArrayList;
import java.util.List;

/** Pure routing rules for the supervisor graph (kept free of framework types so they are unit-testable). */
public final class Routing {
    private Routing() {}

    public static final String FLIGHT = "flight_agent";
    public static final String HOTEL = "hotel_agent";
    public static final String WEATHER = "weather_agent";
    public static final String BUDGET = "budget_agent";
    public static final String ITINERARY = "itinerary_agent";
    public static final String FINAL = "final_agent";
    public static final String APPROVAL = "human_approval";

    /** Execution order of specialists - identical to the original TripBabu AGENT_ORDER. */
    public static final List<String> AGENT_ORDER = List.of(FLIGHT, HOTEL, WEATHER, BUDGET, ITINERARY);

    /** Keeps only known agents, in canonical order, and always ends with the itinerary agent. */
    public static List<String> sanitize(List<String> requested) {
        List<String> out = new ArrayList<>();
        for (String a : AGENT_ORDER) {
            if (requested != null && requested.contains(a) && !out.contains(a)) {
                out.add(a);
            }
        }
        if (!out.contains(ITINERARY)) {
            out.add(ITINERARY);
        }
        return out;
    }

    /** First node after the supervisor. */
    public static String first(List<String> selected) {
        List<String> s = sanitize(selected);
        return s.get(0);
    }

    /** Next selected agent after {@code current}; falls through to the itinerary agent. */
    public static String after(String current, List<String> selected) {
        List<String> s = sanitize(selected);
        int idx = AGENT_ORDER.indexOf(current);
        for (int i = idx + 1; i < AGENT_ORDER.size(); i++) {
            if (s.contains(AGENT_ORDER.get(i))) {
                return AGENT_ORDER.get(i);
            }
        }
        return ITINERARY;
    }

    /** After a human review: approved -> finalize; changes requested -> revise until the cap, then finalize. */
    public static String afterReview(boolean approved, int revisionCount, int maxRevisions) {
        if (approved) {
            return FINAL;
        }
        return revisionCount <= maxRevisions ? ITINERARY : FINAL;
    }
}
