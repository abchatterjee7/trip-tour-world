package org.aadi.triptourworld.guardrails;

import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.tools.AirportDirectory;
import org.aadi.triptourworld.util.Json;
import org.aadi.triptourworld.util.Texts;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Layered input guardrail:
 * <ol>
 *   <li>cheap deterministic checks (empty, too long, prompt-injection, unsafe/illegal requests),</li>
 *   <li>travel-intent keywords / known destinations (no LLM call needed - saves latency and tokens),</li>
 *   <li>an LLM topical classifier only for ambiguous prompts (fails closed to the keyword verdict).</li>
 * </ol>
 */
public final class InputGuardrail {

    public enum Category { OK, EMPTY, TOO_LONG, PROMPT_INJECTION, UNSAFE, OFF_TOPIC }

    public record Result(boolean allowed, Category category, String reason, boolean usedLlm) {
        static Result ok(boolean usedLlm) {
            return new Result(true, Category.OK, "", usedLlm);
        }

        static Result block(Category c, String reason, boolean usedLlm) {
            return new Result(false, c, reason, usedLlm);
        }
    }

    public static final int MAX_CHARS = 1500;
    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private static final Pattern INJECTION = Pattern.compile(
            "(ignore|disregard|forget|override|bypass)\\b.{0,40}\\b(previous|prior|above|earlier|all|any|your|the)\\b.{0,40}"
                    + "\\b(instruction|prompt|rule|guideline|message|restriction)s?"
                    + "|(reveal|show|print|leak|repeat|display|output)\\b.{0,40}"
                    + "(system prompt|hidden prompt|your instructions|your prompt|api[ _-]?key|secret|credential|\\.env)"
                    + "|you are now\\b|act as (dan|an? unrestricted|an? unfiltered)|developer mode|jailbreak|do anything now"
                    + "|</?\\s*(system|assistant)\\s*>|begin system prompt", F);

    private static final Pattern UNSAFE = Pattern.compile(
            "smuggl|traffick|launder|fake (passport|visa|id)|forg(e|ed|ing) (a )?(passport|visa|document)"
                    + "|evade (customs|immigration|police)|bypass (immigration|airport security|customs)"
                    + "|\\bbomb|explosive|hijack|terroris|\\bhow to (kill|poison)|child (abuse|exploit)", F);

    private static final Pattern TRAVEL = Pattern.compile(
            "\\b(trip|trips|travel|travell?ing|traveller|tour|tours|tourism|tourist|vacation|holiday|holidays|getaway|"
                    + "flight|flights|fly|airfare|airline|airlines|airport|layover|hotel|hotels|hostel|resort|stay|stays|"
                    + "accommodation|visit|itinerary|destination|sightseeing|honeymoon|backpack\\w*|trek|trekking|hike|hiking|"
                    + "beach|cruise|visa|passport|packing|weather|forecast|road ?trip|weekend|days? in|nights? in)\\b", F);

    private final ChatModel llm; // may be null -> deterministic only
    private final AirportDirectory airports;

    public InputGuardrail(ChatModel llm, AirportDirectory airports) {
        this.llm = llm;
        this.airports = airports;
    }

    public Result check(String raw) {
        String q = raw == null ? "" : raw.strip();
        if (q.isEmpty()) {
            return Result.block(Category.EMPTY, "Please tell me where you would like to travel.", false);
        }
        if (q.length() > MAX_CHARS) {
            return Result.block(Category.TOO_LONG, "That request is too long. Please keep it under " + MAX_CHARS
                    + " characters.", false);
        }
        if (INJECTION.matcher(q).find()) {
            return Result.block(Category.PROMPT_INJECTION,
                    "That looks like an attempt to change my instructions, so I can't process it. "
                            + "I can help plan trips, flights, stays, weather and budgets.", false);
        }
        if (UNSAFE.matcher(q).find()) {
            return Result.block(Category.UNSAFE,
                    "I can't help with illegal or harmful requests. I'm happy to plan a legal, safe trip instead.", false);
        }
        if (TRAVEL.matcher(q).find() || airports.resolve(q).isPresent()) {
            return Result.ok(false);
        }
        if (llm == null) {
            return offTopic(false);
        }
        try {
            String raw2 = llm.chat(
                    "You are the input guardrail for a travel-planning application. Return strict JSON only.",
                    Texts.render("""
                            Decide whether the request is about travel planning or travel information (destinations, flights,
                            hotels, weather, budgets, visas, transport, sightseeing, food, packing, itineraries).
                            Block clearly unrelated requests. Do not block a travel request just because details are missing.
                            Return strict JSON only: {"allowed": true, "reason": ""}

                            User request:
                            {{q}}
                            """, "q", q));
            var parsed = Json.parseObjectLenient(raw2);
            boolean allowed = !Boolean.FALSE.equals(parsed.get("allowed"));
            return allowed ? Result.ok(true) : offTopic(true);
        } catch (RuntimeException e) {
            return offTopic(false); // classifier unavailable -> keep the deterministic verdict
        }
    }

    private static Result offTopic(boolean usedLlm) {
        return Result.block(Category.OFF_TOPIC,
                "TripTourWorld only helps with travel planning. Ask about a destination, flights, hotels, weather, "
                        + "budget or an itinerary.", usedLlm);
    }

    /** Lower-cased helper exposed for tests/evals. */
    public static String normalize(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }
}
