package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.tools.AirportDirectory;
import org.aadi.triptourworld.util.Json;
import org.aadi.triptourworld.util.Texts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Normalises the supervisor's {@code trip_constraints} (all values bounded Strings, preferences a list). */
public final class Constraints {
    private Constraints() {}

    private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");
    private static final List<String> TEXT_KEYS =
            List.of("destination", "origin", "destination_iata", "origin_iata", "duration", "budget", "travel_style");

    public static Map<String, Object> empty() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : TEXT_KEYS) {
            m.put(k, "");
        }
        m.put("special_preferences", new ArrayList<String>());
        return m;
    }

    public static Map<String, Object> from(Object raw) {
        Map<String, Object> out = empty();
        Map<String, Object> in = Json.asMap(raw);
        for (String k : TEXT_KEYS) {
            Object v = in.get(k);
            out.put(k, v == null ? "" : Texts.truncate(String.valueOf(v).strip(), 200));
        }
        List<String> prefs = new ArrayList<>();
        for (Object p : Json.asList(in.get("special_preferences"))) {
            if (p != null && !String.valueOf(p).isBlank() && prefs.size() < 10) {
                prefs.add(Texts.truncate(String.valueOf(p).strip(), 120));
            }
        }
        out.put("special_preferences", prefs);
        return out;
    }

    /** Fills destination / origin and their IATA codes from the text + directory when the LLM left them blank. */
    public static Map<String, Object> enrich(Map<String, Object> c, String query, AirportDirectory dir, String defaultOriginIata) {
        Map<String, Object> out = new LinkedHashMap<>(c);
        AirportDirectory.Route route = dir.extractRoute(query);

        String destIata = validIata(str(out, "destination_iata"));
        if (destIata.isEmpty()) {
            Optional<AirportDirectory.Airport> a = dir.resolve(str(out, "destination")).or(route::destination);
            destIata = a.map(AirportDirectory.Airport::iata).orElse("");
            if (str(out, "destination").isEmpty()) {
                a.ifPresent(x -> out.put("destination", x.city()));
            }
        }
        out.put("destination_iata", destIata);

        String originIata = validIata(str(out, "origin_iata"));
        if (originIata.isEmpty()) {
            Optional<AirportDirectory.Airport> a = dir.resolve(str(out, "origin")).or(route::origin);
            originIata = a.map(AirportDirectory.Airport::iata).orElse(validIata(defaultOriginIata));
            if (str(out, "origin").isEmpty()) {
                final String code = originIata;
                a.ifPresentOrElse(x -> out.put("origin", x.city()),
                        () -> dir.byIata(code).ifPresent(x -> out.put("origin", x.city())));
            }
        }
        out.put("origin_iata", originIata);
        return out;
    }

    public static String validIata(String s) {
        String u = s == null ? "" : s.strip().toUpperCase();
        return IATA.matcher(u).matches() ? u : "";
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
