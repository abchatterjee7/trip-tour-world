package org.aadi.triptourworld.tools;

import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.http.HttpJson;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.util.Json;
import org.aadi.triptourworld.util.Texts;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** AviationStack: live/scheduled flights between two IATA codes (replaces the aviationstack MCP server). */
public final class FlightTool {
    private final Settings cfg;
    private final HttpJson http;
    private final LangSmithTracer tracer;

    public FlightTool(Settings cfg, HttpJson http, LangSmithTracer tracer) {
        this.cfg = cfg;
        this.http = http;
        this.tracer = tracer;
    }

    public boolean available() {
        return !Texts.blank(cfg.aviationKey());
    }

    public String lookup(String originIata, String destinationIata) {
        if (!available()) {
            throw new ToolException("AVIATIONSTACK_API_KEY is not configured");
        }
        try (LangSmithTracer.Span span = tracer.childOfCurrent("aviationstack.flights", "tool",
                Json.obj("dep_iata", originIata, "arr_iata", destinationIata))) {
            try {
                String url = cfg.aviationBaseUrl().replaceAll("/+$", "") + "/flights?access_key="
                        + HttpJson.encode(cfg.aviationKey())
                        + "&dep_iata=" + HttpJson.encode(originIata)
                        + "&arr_iata=" + HttpJson.encode(destinationIata)
                        + "&limit=25";
                HttpJson.Response r = http.get(url, Map.of(), Duration.ofSeconds(20));
                if (!r.ok()) {
                    throw new ToolException("AviationStack HTTP " + r.status());
                }
                String out = format(Json.parse(r.body()), originIata, destinationIata);
                span.output("summary", Texts.truncate(out, 600));
                return out;
            } catch (IOException e) {
                ToolException te = new ToolException("AviationStack unreachable: " + e.getMessage(), e);
                span.fail(te);
                throw te;
            } catch (ToolException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    static String format(Object json, String origin, String dest) {
        String err = Json.str(json, "error", "message");
        if (!err.isEmpty()) {
            throw new ToolException("AviationStack error: " + Texts.truncate(err, 200));
        }
        List<Object> data = Json.asList(Json.at(json, "data"));
        if (data.isEmpty()) {
            return "No scheduled flights returned for " + origin + " -> " + dest
                    + " (the free AviationStack plan only covers recent/live flights).";
        }
        Set<String> airlines = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder("Live/scheduled flights " + origin + " -> " + dest + ":\n");
        int shown = 0;
        for (Object f : data) {
            String airline = Json.str(f, "airline", "name");
            if (!airline.isEmpty()) {
                airlines.add(airline);
            }
            if (shown < 6) {
                sb.append("- ").append(airline).append(' ').append(Json.str(f, "flight", "iata"))
                        .append(": dep ").append(Json.str(f, "departure", "scheduled"))
                        .append(" -> arr ").append(Json.str(f, "arrival", "scheduled"))
                        .append(" (").append(Json.str(f, "flight_status")).append(")\n");
                shown++;
            }
        }
        sb.append("Airlines seen on this route: ").append(String.join(", ", airlines)).append('.');
        return sb.toString();
    }
}
