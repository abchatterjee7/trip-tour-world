package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.tools.ToolException;
import org.aadi.triptourworld.tools.WeatherTool;
import java.util.Map;

/** Weather specialist: OpenWeather current + outlook, or seasonal-guidance fallback. */
public final class WeatherNode implements AgentNode {
    private final WeatherTool weather;

    public WeatherNode(WeatherTool weather) {
        this.weather = weather;
    }

    @Override
    public String name() {
        return Routing.WEATHER;
    }

    @Override
    public Map<String, Object> apply(TravelState s) {
        String city = String.valueOf(s.map(TravelState.CONSTRAINTS).getOrDefault("destination", "")).strip();
        String result;
        String event;
        try {
            result = weather.forecast(city);
            event = "Weather agent: live data for " + city;
        } catch (ToolException e) {
            result = "Live weather information for " + (city.isEmpty() ? "the destination" : city)
                    + " is temporarily unavailable. Give general seasonal guidance and advise the traveler to verify "
                    + "the forecast before departure.";
            event = "Weather agent: fallback (" + e.getMessage() + ")";
        }
        return Map.of(TravelState.WEATHER_RESULTS, result, TravelState.EVENTS, event);
    }
}
