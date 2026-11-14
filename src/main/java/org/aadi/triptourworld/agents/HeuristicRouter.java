package org.aadi.triptourworld.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Keyword fallback for the supervisor. Used when the LLM is unavailable or returns unparsable JSON,
 * and by the offline routing evals. Mirrors the "safe fallback" idea of the original: when unsure,
 * run the whole workflow.
 */
public final class HeuristicRouter {
    private HeuristicRouter() {}

    private static final int F = Pattern.CASE_INSENSITIVE;
    private static final Pattern FLIGHT = Pattern.compile("\\b(flight|flights|fly|flying|airfare|airline|airlines|airport|ticket|tickets|layover)\\b", F);
    private static final Pattern HOTEL = Pattern.compile("\\b(hotel|hotels|stay|stays|accommodation|hostel|resort|lodging|airbnb|where to stay)\\b", F);
    private static final Pattern WEATHER = Pattern.compile("\\b(weather|climate|forecast|rain|monsoon|temperature|season|packing|pack|snow)\\b", F);
    private static final Pattern BUDGET = Pattern.compile("(\\bbudget\\b|\\bcost\\b|\\bcheap\\w*|\\bprice\\w*|afford\\w*|\\bexpensive\\b|₹|\\binr\\b|\\busd\\b|\\$|\\blakh\\b|under \\d)", F);
    private static final Pattern FULL_PLAN = Pattern.compile("\\b(plan|planning|trip|itinerary|tour|vacation|holiday|honeymoon|\\d+[- ]?days?|\\d+[- ]?nights?)\\b", F);

    public static List<String> route(String query) {
        String q = query == null ? "" : query;
        List<String> picked = new ArrayList<>();
        if (FLIGHT.matcher(q).find()) picked.add(Routing.FLIGHT);
        if (HOTEL.matcher(q).find()) picked.add(Routing.HOTEL);
        if (WEATHER.matcher(q).find()) picked.add(Routing.WEATHER);
        if (BUDGET.matcher(q).find()) picked.add(Routing.BUDGET);

        boolean fullPlan = FULL_PLAN.matcher(q).find();
        if (picked.isEmpty() || fullPlan) {
            return Routing.sanitize(Routing.AGENT_ORDER); // full workflow, like the original fallback
        }
        return Routing.sanitize(picked);
    }
}
