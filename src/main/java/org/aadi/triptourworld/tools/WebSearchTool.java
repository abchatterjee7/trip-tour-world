package org.aadi.triptourworld.tools;

import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.http.HttpJson;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.util.Json;
import org.aadi.triptourworld.util.Texts;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Tavily web search over its REST API (replaces the Tavily MCP server) - used by the hotel agent. */
public final class WebSearchTool {
    private final Settings cfg;
    private final HttpJson http;
    private final LangSmithTracer tracer;

    public WebSearchTool(Settings cfg, HttpJson http, LangSmithTracer tracer) {
        this.cfg = cfg;
        this.http = http;
        this.tracer = tracer;
    }

    public boolean available() {
        return !Texts.blank(cfg.tavilyKey());
    }

    public String search(String query, int maxResults) {
        if (!available()) {
            throw new ToolException("TAVILY_API_KEY is not configured");
        }
        try (LangSmithTracer.Span span = tracer.childOfCurrent("tavily.search", "tool", Json.obj("query", query))) {
            try {
                String body = Json.write(Json.obj("query", query, "max_results", maxResults,
                        "search_depth", "basic", "include_answer", false));
                HttpJson.Response r = http.postJson(cfg.tavilyBaseUrl().replaceAll("/+$", "") + "/search",
                        Map.of("Authorization", "Bearer " + cfg.tavilyKey()), body, Duration.ofSeconds(25));
                if (!r.ok()) {
                    throw new ToolException("Tavily HTTP " + r.status());
                }
                String out = format(Json.parse(r.body()));
                span.output("summary", Texts.truncate(out, 600));
                return out;
            } catch (IOException e) {
                ToolException te = new ToolException("Tavily unreachable: " + e.getMessage(), e);
                span.fail(te);
                throw te;
            } catch (ToolException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    static String format(Object json) {
        List<Object> results = Json.asList(Json.at(json, "results"));
        if (results.isEmpty()) {
            throw new ToolException("Tavily returned no results");
        }
        StringBuilder sb = new StringBuilder();
        for (Object r : results) {
            sb.append("- ").append(Json.str(r, "title")).append(" (").append(Json.str(r, "url")).append("): ")
                    .append(Texts.truncate(Json.str(r, "content").replaceAll("\\s+", " "), 320)).append('\n');
        }
        return sb.toString().strip();
    }
}
