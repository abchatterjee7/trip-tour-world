package org.aadi.triptourworld.graph;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import org.aadi.triptourworld.agents.Agents;
import org.aadi.triptourworld.agents.Routing;
import org.aadi.triptourworld.config.Settings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

/**
 * Builds the TripTourWorld supervisor graph with LangGraph4j.
 *
 * <pre>
 * START -> input_guardrail --blocked--> guardrail_blocked -> END
 *                          --ok-------> supervisor --(dynamic)--> flight -> hotel -> weather -> budget -> itinerary
 * itinerary -> [INTERRUPT] human_approval --approved--------> final_agent -> END
 *                                         --changes requested-> itinerary (revision loop, capped)
 * </pre>
 * This class and {@link TripGraphRunner} are the only places that touch LangGraph4j's API.
 */
public final class TripGraphFactory {
    private TripGraphFactory() {}

    public static CompiledGraph<TravelState> build(Agents a, MemorySaver saver, Settings settings) throws GraphStateException {
        // Every node an edge may route to (all of them must exist in the graph).
        Map<String, String> specialists = new LinkedHashMap<>();
        for (String n : Routing.AGENT_ORDER) {
            specialists.put(n, n);
        }

        StateGraph<TravelState> g = new StateGraph<>(TravelState.SCHEMA, TravelState::new)
                .addNode("input_guardrail", node_async(a.guardrail::apply))
                .addNode(a.blocked.name(), node_async(a.blocked::apply))
                .addNode(a.supervisor.name(), node_async(a.supervisor::apply))
                .addNode(Routing.FLIGHT, node_async(a.flight::apply))
                .addNode(Routing.HOTEL, node_async(a.hotel::apply))
                .addNode(Routing.WEATHER, node_async(a.weather::apply))
                .addNode(Routing.BUDGET, node_async(a.budget::apply))
                .addNode(Routing.ITINERARY, node_async(a.itinerary::apply))
                .addNode(Routing.APPROVAL, node_async(a.approval::apply))
                .addNode(Routing.FINAL, node_async(a.finalAgent::apply));

        g.addEdge(START, "input_guardrail");
        g.addConditionalEdges("input_guardrail",
                edge_async(s -> s.bool(TravelState.GUARDRAIL_ALLOWED) ? "supervisor" : "guardrail_blocked"),
                Map.of("supervisor", "supervisor", "guardrail_blocked", "guardrail_blocked"));
        g.addEdge("guardrail_blocked", END);

        g.addConditionalEdges("supervisor",
                edge_async(s -> Routing.first(s.strings(TravelState.SELECTED_AGENTS))), specialists);
        for (String agent : Routing.AGENT_ORDER) {
            if (!Routing.ITINERARY.equals(agent)) {
                g.addConditionalEdges(agent,
                        edge_async(s -> Routing.after(agent, s.strings(TravelState.SELECTED_AGENTS))), specialists);
            }
        }

        g.addEdge(Routing.ITINERARY, Routing.APPROVAL);
        g.addConditionalEdges(Routing.APPROVAL,
                edge_async(s -> Routing.afterReview(s.bool(TravelState.APPROVED),
                        s.integer(TravelState.REVISION_COUNT), settings.maxRevisions())),
                Map.of(Routing.FINAL, Routing.FINAL, Routing.ITINERARY, Routing.ITINERARY));
        g.addEdge(Routing.FINAL, END);

        CompileConfig cfg = CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptBefore(Routing.APPROVAL) // human-in-the-loop pause
                .build();
        return g.compile(cfg);
    }
}
