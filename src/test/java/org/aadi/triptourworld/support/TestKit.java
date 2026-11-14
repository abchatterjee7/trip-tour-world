package org.aadi.triptourworld.support;

import org.aadi.triptourworld.agents.Agents;
import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.guardrails.InputGuardrail;
import org.aadi.triptourworld.guardrails.OutputGuardrail;
import org.aadi.triptourworld.http.HttpJson;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.tools.AirportDirectory;
import org.aadi.triptourworld.tools.FlightTool;
import org.aadi.triptourworld.tools.WeatherTool;
import org.aadi.triptourworld.tools.WebSearchTool;
import java.util.HashMap;
import java.util.Map;

/** Builds the full agent stack against a fake LLM, with no live tool keys (so every tool uses its fallback). */
public final class TestKit {
    public final Settings settings;
    public final ChatModel llm;
    public final LangSmithTracer tracer;
    public final InputGuardrail guardrail;
    public final Agents agents;

    public TestKit(ChatModel llm, Map<String, String> extraEnv) {
        Map<String, String> env = new HashMap<>(extraEnv);
        env.putIfAbsent("GROQ_API_KEY", "test-key-123456");
        this.settings = Settings.load(env::get);
        this.llm = llm;
        HttpJson http = new HttpJson();
        this.tracer = new LangSmithTracer(settings, http);
        AirportDirectory dir = AirportDirectory.fromClasspath();
        this.guardrail = new InputGuardrail(llm, dir);
        this.agents = new Agents(settings, llm, tracer, guardrail, new OutputGuardrail(), dir,
                new FlightTool(settings, http, tracer), new WebSearchTool(settings, http, tracer), new WeatherTool(settings, http, tracer));
    }

    public TestKit(ChatModel llm) {
        this(llm, Map.of());
    }
}
