package org.aadi.triptourworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aadi.triptourworld.agents.Constraints;
import org.aadi.triptourworld.agents.HeuristicRouter;
import org.aadi.triptourworld.agents.Routing;
import org.aadi.triptourworld.tools.AirportDirectory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingTest {
    @Test
    void sanitizeKeepsCanonicalOrderAndAlwaysEndsWithItinerary() {
        assertEquals(List.of("flight_agent", "budget_agent", "itinerary_agent"),
                Routing.sanitize(List.of("budget_agent", "bogus_agent", "flight_agent")));
        assertEquals(List.of("itinerary_agent"), Routing.sanitize(List.of()));
    }

    @Test
    void walksSelectedAgentsInOrder() {
        List<String> sel = List.of("flight_agent", "weather_agent", "itinerary_agent");
        assertEquals("flight_agent", Routing.first(sel));
        assertEquals("weather_agent", Routing.after("flight_agent", sel));
        assertEquals("itinerary_agent", Routing.after("weather_agent", sel));
    }

    @Test
    void reviewLoopIsCapped() {
        assertEquals("final_agent", Routing.afterReview(true, 0, 2));
        assertEquals("itinerary_agent", Routing.afterReview(false, 1, 2));
        assertEquals("itinerary_agent", Routing.afterReview(false, 2, 2));
        assertEquals("final_agent", Routing.afterReview(false, 3, 2));
    }

    @Test
    void heuristicRouterPicksSpecialists() {
        List<String> flights = HeuristicRouter.route("Cheapest flights from Delhi to Dubai");
        assertTrue(flights.contains("flight_agent") && !flights.contains("hotel_agent"));
        assertTrue(HeuristicRouter.route("Where should I stay in Kyoto? hotels").contains("hotel_agent"));
        assertEquals(Routing.AGENT_ORDER, HeuristicRouter.route("Plan a 7-day Switzerland trip"));
        assertEquals(Routing.AGENT_ORDER, HeuristicRouter.route("hmm"));
    }

    @Test
    void airportDirectoryResolvesPlacesAndRoutes() {
        AirportDirectory dir = AirportDirectory.fromClasspath();
        assertTrue(dir.size() > 80);
        assertEquals("BLR", dir.resolve("Bangalore").orElseThrow().iata());
        assertEquals("GOI", dir.resolve("a beach holiday in goa").orElseThrow().iata());
        AirportDirectory.Route r = dir.extractRoute("Plan a 5 day trip from Hyderabad to London");
        assertEquals("HYD", r.origin().orElseThrow().iata());
        assertEquals("LHR", r.destination().orElseThrow().iata());
        assertEquals("GOI", dir.extractRoute("Plan 4 days in Goa").destination().orElseThrow().iata());
        assertTrue(dir.resolve("Atlantis").isEmpty());
    }

    @Test
    void constraintsAreEnrichedFromTheQuery() {
        AirportDirectory dir = AirportDirectory.fromClasspath();
        Map<String, Object> c = Constraints.enrich(Constraints.empty(), "Trip from Mumbai to Paris for a week", dir, "DEL");
        assertEquals("CDG", c.get("destination_iata"));
        assertEquals("BOM", c.get("origin_iata"));
        Map<String, Object> d = Constraints.enrich(Constraints.empty(), "Plan a Paris holiday", dir, "DEL");
        assertEquals("DEL", d.get("origin_iata")); // falls back to DEFAULT_ORIGIN_IATA
        assertEquals("", Constraints.validIata("toolong"));
    }
}
