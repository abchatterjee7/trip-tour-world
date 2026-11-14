package org.aadi.triptourworld.config;

import org.aadi.triptourworld.agents.Agents;
import org.aadi.triptourworld.eval.EvalService;
import org.aadi.triptourworld.graph.TripGraphRunner;
import org.aadi.triptourworld.guardrails.InputGuardrail;
import org.aadi.triptourworld.guardrails.OutputGuardrail;
import org.aadi.triptourworld.http.HttpJson;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.llm.GroqChatModel;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.persistence.RepositoryFactory;
import org.aadi.triptourworld.persistence.TripRepository;
import org.aadi.triptourworld.service.TripService;
import org.aadi.triptourworld.tools.AirportDirectory;
import org.aadi.triptourworld.tools.FlightTool;
import org.aadi.triptourworld.tools.WeatherTool;
import org.aadi.triptourworld.tools.WebSearchTool;
import java.util.logging.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free core (agents, tools, guardrails, graph) into the Spring context. */
@Configuration
public class AppConfig {
    private static final Logger LOG = Logger.getLogger(AppConfig.class.getName());

    @Bean
    Settings settings() {
        Settings s = Settings.load();
        LOG.info("Config: llm=" + (s.llmConfigured() ? s.groqModelId() : "NOT CONFIGURED")
                + ", database=" + (s.databaseConfigured() ? "neondb" : "memory")
                + ", tracing=" + s.tracingEnabled()
                + ", apiKeyRequired=" + s.apiKeyRequired()
                + ", flights=" + !s.aviationKey().isBlank()
                + ", weather=" + !s.openWeatherKey().isBlank()
                + ", search=" + !s.tavilyKey().isBlank());
        if (!s.apiKeyRequired()) {
            LOG.warning("APP_API_KEY is not set: /api/** is OPEN. Set it before exposing the app publicly.");
        }
        return s;
    }

    @Bean
    HttpJson httpJson() {
        return new HttpJson();
    }

    @Bean
    LangSmithTracer langSmithTracer(Settings settings, HttpJson http) {
        return new LangSmithTracer(settings, http);
    }

    @Bean
    ChatModel chatModel(Settings settings, HttpJson http, LangSmithTracer tracer) {
        return new GroqChatModel(settings, http, tracer);
    }

    @Bean
    AirportDirectory airportDirectory() {
        return AirportDirectory.fromClasspath();
    }

    @Bean
    InputGuardrail inputGuardrail(Settings settings, ChatModel model, AirportDirectory airports) {
        return new InputGuardrail(settings.llmConfigured() ? model : null, airports);
    }

    @Bean
    OutputGuardrail outputGuardrail() {
        return new OutputGuardrail();
    }

    @Bean
    FlightTool flightTool(Settings s, HttpJson http, LangSmithTracer t) {
        return new FlightTool(s, http, t);
    }

    @Bean
    WeatherTool weatherTool(Settings s, HttpJson http, LangSmithTracer t) {
        return new WeatherTool(s, http, t);
    }

    @Bean
    WebSearchTool webSearchTool(Settings s, HttpJson http, LangSmithTracer t) {
        return new WebSearchTool(s, http, t);
    }

    @Bean
    Agents agents(Settings s, ChatModel llm, LangSmithTracer tracer, InputGuardrail in, OutputGuardrail out,
                  AirportDirectory airports, FlightTool flights, WebSearchTool search, WeatherTool weather) {
        return new Agents(s, llm, tracer, in, out, airports, flights, search, weather);
    }

    /** Chooses NeonDB or memory; closing this bean closes the connection pool. */
    @Bean
    RepositoryFactory.Result repositoryHandle(Settings settings) {
        return RepositoryFactory.create(settings);
    }

    @Bean
    TripRepository tripRepository(RepositoryFactory.Result handle) {
        return handle.repository();
    }

    @Bean
    TripGraphRunner tripGraphRunner(Agents agents, Settings settings) throws Exception {
        return new TripGraphRunner(agents, settings);
    }

    @Bean
    TripService tripService(Settings s, TripRepository repo, TripGraphRunner runner, Agents agents, LangSmithTracer t) {
        return new TripService(s, repo, runner, agents, t);
    }

    @Bean
    EvalService evalService(Settings s, InputGuardrail g, Agents agents, TripRepository repo, ChatModel model) {
        return new EvalService(s, g, agents.supervisorRaw, repo, model);
    }
}
