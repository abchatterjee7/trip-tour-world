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

/** OpenWeather current conditions + 5-day outlook (replaces the custom weather MCP server). */
public final class WeatherTool {
    private final Settings cfg;
    private final HttpJson http;
    private final LangSmithTracer tracer;

    public WeatherTool(Settings cfg, HttpJson http, LangSmithTracer tracer) {
        this.cfg = cfg;
        this.http = http;
        this.tracer = tracer;
    }

    public boolean available() {
        return !Texts.blank(cfg.openWeatherKey());
    }

    public String forecast(String city) {
        if (!available()) {
            throw new ToolException("OPENWEATHER_API_KEY is not configured");
        }
        if (Texts.blank(city)) {
            throw new ToolException("destination city is unknown");
        }
        try (LangSmithTracer.Span span = tracer.childOfCurrent("openweather.forecast", "tool", Json.obj("city", city))) {
            try {
                String base = cfg.openWeatherBaseUrl().replaceAll("/+$", "");
                String common = "?q=" + HttpJson.encode(city) + "&units=metric&appid=" + HttpJson.encode(cfg.openWeatherKey());
                HttpJson.Response now = http.get(base + "/weather" + common, Map.of(), Duration.ofSeconds(15));
                if (!now.ok()) {
                    throw new ToolException("OpenWeather could not find '" + city + "' (HTTP " + now.status() + ")");
                }
                HttpJson.Response fc = http.get(base + "/forecast" + common, Map.of(), Duration.ofSeconds(15));
                String out = format(city, Json.parse(now.body()), fc.ok() ? Json.parse(fc.body()) : null);
                span.output("summary", Texts.truncate(out, 600));
                return out;
            } catch (IOException e) {
                ToolException te = new ToolException("OpenWeather unreachable: " + e.getMessage(), e);
                span.fail(te);
                throw te;
            } catch (ToolException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    static String format(String city, Object now, Object forecast) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current weather in ").append(Json.str(now, "name").isEmpty() ? city : Json.str(now, "name")).append(": ")
                .append(Json.str(now, "main", "temp")).append("°C (feels like ").append(Json.str(now, "main", "feels_like"))
                .append("°C), ").append(Json.str(now, "weather", 0, "description"))
                .append(", humidity ").append(Json.str(now, "main", "humidity")).append("%, wind ")
                .append(Json.str(now, "wind", "speed")).append(" m/s.\n");
        List<Object> list = forecast == null ? List.of() : Json.asList(Json.at(forecast, "list"));
        if (!list.isEmpty()) {
            sb.append("Outlook (every ~24h):\n");
            for (int i = 0; i < list.size(); i += 8) {
                Object e = list.get(i);
                sb.append("- ").append(Json.str(e, "dt_txt")).append(": ").append(Json.str(e, "main", "temp"))
                        .append("°C, ").append(Json.str(e, "weather", 0, "description")).append('\n');
            }
        }
        return sb.toString().strip();
    }
}
