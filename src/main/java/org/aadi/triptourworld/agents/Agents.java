package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.guardrails.InputGuardrail;
import org.aadi.triptourworld.guardrails.OutputGuardrail;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.tools.AirportDirectory;
import org.aadi.triptourworld.tools.FlightTool;
import org.aadi.triptourworld.tools.WeatherTool;
import org.aadi.triptourworld.tools.WebSearchTool;

/** All workflow nodes, already wrapped with LangSmith tracing. Shared by the graph and the restart-recovery path. */
public final class Agents {
    public final AgentNode guardrail;
    public final AgentNode blocked;
    public final SupervisorNode supervisorRaw;
    public final AgentNode supervisor;
    public final AgentNode flight;
    public final AgentNode hotel;
    public final AgentNode weather;
    public final AgentNode budget;
    public final AgentNode itinerary;
    public final AgentNode approval;
    public final AgentNode finalAgent;

    public Agents(Settings cfg, ChatModel llm, LangSmithTracer tracer, InputGuardrail inputGuardrail,
                  OutputGuardrail outputGuardrail, AirportDirectory airports, FlightTool flights,
                  WebSearchTool search, WeatherTool weatherTool) {
        this.supervisorRaw = new SupervisorNode(llm, cfg, airports);
        this.guardrail = new TracedNode(new GuardrailNode(inputGuardrail), tracer);
        this.blocked = new TracedNode(new BlockedResponseNode(), tracer);
        this.supervisor = new TracedNode(supervisorRaw, tracer);
        this.flight = new TracedNode(new FlightNode(llm, flights, cfg), tracer);
        this.hotel = new TracedNode(new HotelNode(search), tracer);
        this.weather = new TracedNode(new WeatherNode(weatherTool), tracer);
        this.budget = new TracedNode(new BudgetNode(llm), tracer);
        this.itinerary = new TracedNode(new ItineraryNode(llm), tracer);
        this.approval = new TracedNode(new ApprovalNode(cfg), tracer);
        this.finalAgent = new TracedNode(new FinalNode(llm, outputGuardrail), tracer);
    }
}
