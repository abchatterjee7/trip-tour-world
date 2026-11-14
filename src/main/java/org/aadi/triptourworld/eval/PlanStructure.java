package org.aadi.triptourworld.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic quality checks for a finished plan (the "structure" eval). */
public final class PlanStructure {
    private PlanStructure() {}

    public static final List<String> REQUIRED_SECTIONS = List.of(
            "Trip Summary", "Flight Information", "Hotel Suggestions", "Weather Information",
            "Day-by-Day Itinerary", "Estimated Budget", "Final Recommendations");

    private static final Pattern LEAKED_SECRET = Pattern.compile("gsk_[A-Za-z0-9]{8,}|tvly-[A-Za-z0-9]{8,}|lsv2_[A-Za-z0-9_]{8,}|postgres(ql)?://");

    /** Returns the list of problems found (empty list = pass). */
    public static List<String> problems(String plan) {
        List<String> out = new ArrayList<>();
        String text = plan == null ? "" : plan;
        if (text.length() < 400) {
            out.add("plan is too short (" + text.length() + " chars)");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String section : REQUIRED_SECTIONS) {
            if (!lower.contains(section.toLowerCase(Locale.ROOT))) {
                out.add("missing section: " + section);
            }
        }
        if (!lower.contains("verify everything before booking")) {
            out.add("missing verify-before-booking disclaimer");
        }
        if (LEAKED_SECRET.matcher(text).find()) {
            out.add("possible secret leaked in output");
        }
        return out;
    }
}
