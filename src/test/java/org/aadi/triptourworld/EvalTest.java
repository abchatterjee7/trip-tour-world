package org.aadi.triptourworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aadi.triptourworld.eval.EvalService;
import org.aadi.triptourworld.eval.PlanStructure;
import org.aadi.triptourworld.guardrails.InputGuardrail;
import org.aadi.triptourworld.guardrails.OutputGuardrail;
import org.aadi.triptourworld.persistence.InMemoryTripRepository;
import org.aadi.triptourworld.support.ScriptedChatModel;
import org.aadi.triptourworld.support.TestKit;
import org.aadi.triptourworld.util.Json;
import java.util.List;
import java.util.Map;

import org.aadi.triptourworld.tools.AirportDirectory;
import org.junit.jupiter.api.Test;

class EvalTest {
    @Test
    void offlineEvalsPassWithTheDeterministicPaths() {
        // No GROQ key -> guardrail uses its deterministic layer, routing uses the keyword router.
        TestKit kit = new TestKit(new ScriptedChatModel(), Map.of("GROQ_API_KEY", "your_groq_api_key_here"));
        InMemoryTripRepository repo = new InMemoryTripRepository();
        InputGuardrail offline =
                new InputGuardrail(null, AirportDirectory.fromClasspath());
        EvalService evals = new EvalService(kit.settings, offline, kit.agents.supervisorRaw, repo, kit.llm);

        Map<String, Object> report = evals.run("all");
        assertEquals(report.get("total"), report.get("passed"), Json.write(report));
        assertTrue(((Number) report.get("total")).intValue() >= 18);
        assertTrue(evals.latestJson().isPresent());
        assertThrows(IllegalArgumentException.class, () -> evals.run("nonsense"));
    }

    @Test
    void structureCheckerFlagsBadPlans() {
        assertTrue(PlanStructure.problems("short and wrong gsk_abcdefghijkl").size() >= 3);
        assertEquals(List.of(), PlanStructure.problems(new OutputGuardrail().sanitize(
                String.join("\n", PlanStructure.REQUIRED_SECTIONS.stream().map(s -> "## " + s + "\n" + "x".repeat(80)).toList()))));
    }
}
