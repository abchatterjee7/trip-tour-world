package org.aadi.triptourworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.http.HttpJson;
import org.aadi.triptourworld.llm.GroqChatModel;
import org.aadi.triptourworld.llm.LlmException;
import org.aadi.triptourworld.obs.LangSmithTracer;
import org.aadi.triptourworld.support.FakeHttpServer;
import org.aadi.triptourworld.support.FakeHttpServer.Resp;
import org.aadi.triptourworld.tools.FlightTool;
import org.aadi.triptourworld.tools.ToolException;
import org.aadi.triptourworld.tools.WeatherTool;
import org.aadi.triptourworld.tools.WebSearchTool;
import org.aadi.triptourworld.util.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientsTest {
    private static Settings settings(Map<String, String> extra) {
        Map<String, String> env = new java.util.HashMap<>(extra);
        return Settings.load(env::get);
    }

    private static String completion(String text) {
        return Json.write(Json.obj("choices", Json.arr(Json.obj("message", Json.obj("role", "assistant", "content", text),
                "finish_reason", "stop")), "usage", Json.obj("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }

    @Test
    void groqSendsOpenAiStyleRequestAndStripsThinkTags() throws Exception {
        try (FakeHttpServer srv = new FakeHttpServer(r -> new Resp(200, completion("<think>hmm</think>Hello traveller")))) {
            Settings s = settings(Map.of("GROQ_API_KEY", "gsk_test", "GROQ_BASE_URL", srv.url() + "/openai/v1"));
            GroqChatModel m = new GroqChatModel(s, new HttpJson(), new LangSmithTracer(s, new HttpJson()), ms -> { });
            assertEquals("Hello traveller", m.chat("sys", "usr"));
            FakeHttpServer.Req req = srv.received().get(0);
            assertEquals("/openai/v1/chat/completions", req.path());
            assertEquals("Bearer gsk_test", req.authorization());
            Object body = Json.parse(req.body());
            assertEquals("openai/gpt-oss-20b", Json.str(body, "model"));
            assertEquals("sys", Json.str(body, "messages", 0, "content"));
            assertEquals("low", Json.str(body, "reasoning_effort"));
        }
    }

    @Test
    void groqRetriesOnRateLimitThenSucceeds() throws Exception {
        AtomicInteger n = new AtomicInteger();
        try (FakeHttpServer srv = new FakeHttpServer(r -> n.incrementAndGet() < 3
                ? new Resp(429, "{\"error\":{\"message\":\"rate limit\"}}") : new Resp(200, completion("ok")))) {
            Settings s = settings(Map.of("GROQ_API_KEY", "k-test-1", "GROQ_BASE_URL", srv.url()));
            List<Long> sleeps = new ArrayList<>();
            GroqChatModel m = new GroqChatModel(s, new HttpJson(), new LangSmithTracer(s, new HttpJson()), sleeps::add);
            assertEquals("ok", m.chat("a", "b"));
            assertEquals(3, srv.received().size());
            assertEquals(2, sleeps.size());
        }
    }

    @Test
    void groqDoesNotRetryClientErrorsAndFailsWithoutKey() throws Exception {
        try (FakeHttpServer srv = new FakeHttpServer(r -> new Resp(400, "{\"error\":{\"message\":\"bad model\"}}"))) {
            Settings s = settings(Map.of("GROQ_API_KEY", "k-test-1", "GROQ_BASE_URL", srv.url()));
            GroqChatModel m = new GroqChatModel(s, new HttpJson(), new LangSmithTracer(s, new HttpJson()), ms -> { });
            LlmException e = assertThrows(LlmException.class, () -> m.chat("a", "b"));
            assertTrue(e.getMessage().contains("bad model"));
            assertEquals(1, srv.received().size());
        }
        Settings none = settings(Map.of());
        assertThrows(LlmException.class, () -> new GroqChatModel(none, new HttpJson(), new LangSmithTracer(none, new HttpJson())).chat("a", "b"));
    }

    @Test
    void toolsParseProviderResponses() throws Exception {
        String flights = Json.write(Json.obj("data", Json.arr(Json.obj("flight_status", "scheduled",
                "airline", Json.obj("name", "IndiGo"), "flight", Json.obj("iata", "6E123"),
                "departure", Json.obj("scheduled", "2026-12-01T08:00:00"), "arrival", Json.obj("scheduled", "2026-12-01T09:30:00")))));
        String weather = Json.write(Json.obj("name", "Goa", "main", Json.obj("temp", 31.2, "feels_like", 35, "humidity", 70),
                "weather", Json.arr(Json.obj("description", "clear sky")), "wind", Json.obj("speed", 3.1)));
        String forecast = Json.write(Json.obj("list", Json.arr(Json.obj("dt_txt", "2026-12-02 12:00:00",
                "main", Json.obj("temp", 30), "weather", Json.arr(Json.obj("description", "haze"))))));
        String tavily = Json.write(Json.obj("results", Json.arr(Json.obj("title", "Taj Resort", "url", "https://t.example", "content", "Great beach hotel"))));

        try (FakeHttpServer srv = new FakeHttpServer(r -> {
            if (r.path().endsWith("/flights")) return new Resp(200, flights);
            if (r.path().endsWith("/weather")) return new Resp(200, weather);
            if (r.path().endsWith("/forecast")) return new Resp(200, forecast);
            if (r.path().endsWith("/search")) return new Resp(200, tavily);
            return new Resp(404, "{}");
        })) {
            Settings s = settings(Map.of("AVIATIONSTACK_API_KEY", "av-key-1", "AVIATIONSTACK_BASE_URL", srv.url(),
                    "OPENWEATHER_API_KEY", "ow-key-1", "OPENWEATHER_BASE_URL", srv.url(),
                    "TAVILY_API_KEY", "tv-key-1", "TAVILY_BASE_URL", srv.url()));
            LangSmithTracer t = new LangSmithTracer(s, new HttpJson());
            String f = new FlightTool(s, new HttpJson(), t).lookup("HYD", "GOI");
            assertTrue(f.contains("IndiGo 6E123") && f.contains("Airlines seen"));
            String w = new WeatherTool(s, new HttpJson(), t).forecast("Goa");
            assertTrue(w.contains("31.2") && w.contains("clear sky") && w.contains("haze"));
            String h = new WebSearchTool(s, new HttpJson(), t).search("hotels in goa", 5);
            assertTrue(h.contains("Taj Resort") && h.contains("https://t.example"));
            assertEquals("Bearer tv-key-1", srv.received().stream().filter(r -> r.path().endsWith("/search")).findFirst().orElseThrow().authorization());
        }
    }

    @Test
    void toolsFailCleanlyWhenUnconfiguredOrProviderErrors() throws Exception {
        Settings none = settings(Map.of());
        LangSmithTracer t = new LangSmithTracer(none, new HttpJson());
        assertThrows(ToolException.class, () -> new FlightTool(none, new HttpJson(), t).lookup("HYD", "GOI"));
        assertThrows(ToolException.class, () -> new WeatherTool(none, new HttpJson(), t).forecast("Goa"));
        assertThrows(ToolException.class, () -> new WebSearchTool(none, new HttpJson(), t).search("x", 3));

        try (FakeHttpServer srv = new FakeHttpServer(r -> new Resp(200, "{\"error\":{\"message\":\"usage limit reached\"}}"))) {
            Settings s = settings(Map.of("AVIATIONSTACK_API_KEY", "av-key-1", "AVIATIONSTACK_BASE_URL", srv.url()));
            ToolException e = assertThrows(ToolException.class, () -> new FlightTool(s, new HttpJson(), new LangSmithTracer(s, new HttpJson())).lookup("HYD", "GOI"));
            assertTrue(e.getMessage().contains("usage limit"));
            assertFalse(e.getMessage().contains("av-key-1"), "API keys must never appear in error messages");
        }
    }

    @Test
    void langSmithRecordsAParentChildRunTree() throws Exception {
        try (FakeHttpServer srv = new FakeHttpServer(r -> new Resp(200, "{}"))) {
            Settings s = settings(Map.of("LANGSMITH_TRACING", "true", "LANGSMITH_API_KEY", "ls-key-1",
                    "LANGSMITH_ENDPOINT", srv.url(), "LANGSMITH_PROJECT", "TripTourWorld"));
            LangSmithTracer tracer = new LangSmithTracer(s, new HttpJson());
            assertTrue(tracer.enabled());
            try (LangSmithTracer.Span root = tracer.root("TripTourWorld.plan", "thread-1", Json.obj("q", "goa"))) {
                try (LangSmithTracer.Span node = tracer.child(root.ref(), "supervisor", "chain", Json.obj())) {
                    try (LangSmithTracer.Span llm = tracer.childOfCurrent("groq.chat", "llm", Json.obj("m", 1))) {
                        llm.output("ok", true);
                    }
                }
            }
            tracer.close(); // flush

            List<Object> posts = new ArrayList<>();
            List<Object> patches = new ArrayList<>();
            for (FakeHttpServer.Req r : srv.received()) {
                assertEquals("/runs/batch", r.path());
                assertEquals("ls-key-1", r.apiKeyHeader());
                Object body = Json.parse(r.body());
                Json.asList(Json.at(body, "post")).forEach(posts::add);
                Json.asList(Json.at(body, "patch")).forEach(patches::add);
            }
            assertEquals(3, posts.size());
            assertEquals(3, patches.size());
            Object rootRun = posts.get(0);
            Object nodeRun = posts.get(1);
            Object llmRun = posts.get(2);
            assertEquals(Json.str(rootRun, "id"), Json.str(nodeRun, "trace_id"));
            assertEquals(Json.str(rootRun, "id"), Json.str(nodeRun, "parent_run_id"));
            assertEquals(Json.str(nodeRun, "id"), Json.str(llmRun, "parent_run_id"));
            assertTrue(Json.str(llmRun, "dotted_order").startsWith(Json.str(nodeRun, "dotted_order") + "."));
            assertEquals("TripTourWorld", Json.str(rootRun, "session_name"));
            assertEquals("thread-1", Json.str(llmRun, "extra", "metadata", "thread_id"));
            assertEquals("llm", Json.str(llmRun, "run_type"));
        }
    }

    @Test
    void langSmithIsAHarmlessNoOpWhenDisabled() {
        Settings s = settings(Map.of());
        LangSmithTracer tracer = new LangSmithTracer(s, new HttpJson());
        assertFalse(tracer.enabled());
        try (LangSmithTracer.Span root = tracer.root("x", "t", Json.obj())) {
            assertTrue(root.ref().valid());
        }
        assertEquals(0, tracer.failedSends());
    }
}
