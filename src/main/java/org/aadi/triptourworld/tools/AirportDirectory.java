package org.aadi.triptourworld.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** City/alias -> IATA lookup backed by a small bundled CSV (replaces the original airportsdata/pycountry use). */
public final class AirportDirectory {

    public record Airport(String iata, String city, String country, List<String> names) {}

    public record Route(Optional<Airport> origin, Optional<Airport> destination) {}

    private record Hit(Airport airport, int position) {}

    private final List<Airport> airports;

    public AirportDirectory(List<Airport> airports) {
        this.airports = List.copyOf(airports);
    }

    public static AirportDirectory fromClasspath() {
        try (InputStream in = AirportDirectory.class.getResourceAsStream("/airports.csv")) {
            if (in == null) {
                return new AirportDirectory(List.of());
            }
            return new AirportDirectory(read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))));
        } catch (IOException e) {
            return new AirportDirectory(List.of());
        }
    }

    static List<Airport> read(BufferedReader reader) throws IOException {
        List<Airport> out = new ArrayList<>();
        String line = reader.readLine(); // header
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split(",", -1);
            if (c.length < 3) {
                continue;
            }
            List<String> names = new ArrayList<>();
            names.add(c[1].trim().toLowerCase(Locale.ROOT));
            if (c.length > 3 && !c[3].isBlank()) {
                for (String a : c[3].split(";")) {
                    if (!a.isBlank()) {
                        names.add(a.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            out.add(new Airport(c[0].trim().toUpperCase(Locale.ROOT), c[1].trim(), c[2].trim(), names));
        }
        return out;
    }

    public int size() {
        return airports.size();
    }

    public Optional<Airport> byIata(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return airports.stream().filter(a -> a.iata().equals(c)).findFirst();
    }

    /** Resolves a free-form place name ("Bangalore", "Goa", "new delhi") to an airport. */
    public Optional<Airport> resolve(String place) {
        if (place == null || place.isBlank()) {
            return Optional.empty();
        }
        List<Hit> hits = scan(place);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0).airport());
    }

    /**
     * Guesses origin and destination from a sentence: "from X ... to Y" wins; otherwise the first
     * place mentioned is the destination when only one is present, else origin then destination.
     */
    public Route extractRoute(String text) {
        List<Hit> hits = scan(text == null ? "" : text);
        if (hits.isEmpty()) {
            return new Route(Optional.empty(), Optional.empty());
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Optional<Airport> from = Optional.empty();
        Optional<Airport> to = Optional.empty();
        for (Hit h : hits) {
            String before = lower.substring(Math.max(0, h.position() - 12), h.position());
            if (from.isEmpty() && before.matches("(?s).*\\b(from|leaving|departing|starting from|out of)\\s*$")) {
                from = Optional.of(h.airport());
            } else if (to.isEmpty() && before.matches("(?s).*\\b(to|in|visit|for|towards|trip to)\\s*$")) {
                to = Optional.of(h.airport());
            }
        }
        if (from.isEmpty() && to.isEmpty()) {
            if (hits.size() == 1) {
                to = Optional.of(hits.get(0).airport());
            } else {
                from = Optional.of(hits.get(0).airport());
                to = Optional.of(hits.get(1).airport());
            }
        } else if (to.isEmpty()) {
            final Optional<Airport> fromFinal = from;
            to = hits.stream().map(Hit::airport).filter(a -> fromFinal.map(f -> !f.equals(a)).orElse(true)).findFirst();
        }
        return new Route(from, to);
    }

    private List<Hit> scan(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        for (Airport a : airports) {
            int best = -1;
            for (String name : a.names()) {
                Matcher m = Pattern.compile("(?<![a-z])" + Pattern.quote(name) + "(?![a-z])").matcher(lower);
                if (m.find() && (best < 0 || m.start() < best)) {
                    best = m.start();
                }
            }
            if (best >= 0) {
                hits.add(new Hit(a, best));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::position));
        return hits;
    }
}
