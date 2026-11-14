package org.aadi.triptourworld.eval;

import org.aadi.triptourworld.agents.SupervisorNode;
import org.aadi.triptourworld.config.Settings;
import org.aadi.triptourworld.guardrails.InputGuardrail;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.guardrails.OutputGuardrail;
import org.aadi.triptourworld.persistence.TripRecord;
import org.aadi.triptourworld.persistence.TripRepository;
import org.aadi.triptourworld.persistence.TripStatus;
import org.aadi.triptourworld.util.Json;
import org.aadi.triptourworld.util.Texts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluation harness for the agent system.
 * <ul>
 *   <li><b>guardrail</b> - labelled prompts: should they be allowed / blocked, and why (safety + relevance).</li>
 *   <li><b>routing</b> - does the supervisor pick the right specialists for a request?</li>
 *   <li><b>structure</b> - do finished plans contain all required sections, the disclaimer and no leaked secrets?</li>
 *   <li><b>quality</b> - LLM-as-judge rubric (1-5) on the latest completed plan (needs GROQ_API_KEY).</li>
 * </ul>
 * Suites that need the LLM are reported as "skipped" rather than failing when it is not configured.
 */
public final class EvalService {
    private final Settings cfg;
    private final InputGuardrail guardrail;
    private final SupervisorNode supervisor;
    private final TripRepository repo;
    private final ChatModel judge;
    private final Map<String, Object> cases;

    public EvalService(Settings cfg, InputGuardrail guardrail, SupervisorNode supervisor, TripRepository repo, ChatModel judge) {
        this.cfg = cfg;
        this.guardrail = guardrail;
        this.supervisor = supervisor;
        this.repo = repo;
        this.judge = judge;
        this.cases = loadCases();
    }

    public Map<String, Object> run(String suite) {
        String s = suite == null || suite.isBlank() ? "all" : suite.toLowerCase();
        List<Object> suites = new ArrayList<>();
        int total = 0;
        int passed = 0;
        for (String name : List.of("guardrail", "routing", "structure", "quality")) {
            if (!s.equals("all") && !s.equals(name)) {
                continue;
            }
            Map<String, Object> r = switch (name) {
                case "guardrail" -> guardrailSuite();
                case "routing" -> routingSuite();
                case "structure" -> structureSuite();
                default -> qualitySuite();
            };
            suites.add(r);
            total += ((Number) r.get("total")).intValue();
            passed += ((Number) r.get("passed")).intValue();
        }
        if (suites.isEmpty()) {
            throw new IllegalArgumentException("Unknown suite '" + suite + "'. Use guardrail, routing, structure, quality or all.");
        }
        Map<String, Object> report = Json.obj("suite", s, "total", total, "passed", passed,
                "accuracy", total == 0 ? 0.0 : Math.round(1000.0 * passed / total) / 10.0,
                "llm", cfg.llmConfigured() ? judge.name() : "not configured (LLM-dependent checks use deterministic paths)",
                "suites", suites);
        repo.saveEval(s, total, passed, Json.write(report));
        return report;
    }

    public Optional<String> latestJson() {
        return repo.latestEval();
    }

    // ------------------------------------------------------------------ suites

    private Map<String, Object> guardrailSuite() {
        List<Object> results = new ArrayList<>();
        int passed = 0;
        for (Object o : Json.asList(cases.get("guardrail"))) {
            String input = Json.str(o, "input");
            boolean expect = Boolean.TRUE.equals(Json.at(o, "expectAllowed"));
            String expectCat = Json.str(o, "category");
            InputGuardrail.Result r = guardrail.check(input);
            boolean ok = r.allowed() == expect && (expectCat.isEmpty() || r.category().name().equals(expectCat));
            if (ok) {
                passed++;
            }
            results.add(Json.obj("id", Json.str(o, "id"), "pass", ok, "input", Texts.truncate(input, 80),
                    "expected", expect ? "ALLOW" : "BLOCK" + (expectCat.isEmpty() ? "" : ":" + expectCat),
                    "actual", (r.allowed() ? "ALLOW" : "BLOCK") + ":" + r.category().name()));
        }
        return suite("guardrail", results, passed, 0);
    }

    private Map<String, Object> routingSuite() {
        List<Object> results = new ArrayList<>();
        int passed = 0;
        for (Object o : Json.asList(cases.get("routing"))) {
            String input = Json.str(o, "input");
            SupervisorNode.Plan plan = supervisor.plan(input, cfg.llmConfigured());
            boolean ok = true;
            for (Object must : Json.asList(Json.at(o, "mustInclude"))) {
                ok &= plan.agents().contains(String.valueOf(must));
            }
            for (Object mustNot : Json.asList(Json.at(o, "mustExclude"))) {
                ok &= !plan.agents().contains(String.valueOf(mustNot));
            }
            if (ok) {
                passed++;
            }
            results.add(Json.obj("id", Json.str(o, "id"), "pass", ok, "input", Texts.truncate(input, 80),
                    "agents", plan.agents(), "router", plan.usedLlm() ? "llm" : "keyword"));
        }
        return suite("routing", results, passed, 0);
    }

    private Map<String, Object> structureSuite() {
        List<Object> results = new ArrayList<>();
        int passed = 0;

        String golden = goldenPlan();
        List<String> goldenProblems = PlanStructure.problems(golden);
        boolean goldenOk = goldenProblems.isEmpty();
        passed += goldenOk ? 1 : 0;
        results.add(Json.obj("id", "golden-sample", "pass", goldenOk, "problems", goldenProblems));

        List<String> badProblems = PlanStructure.problems("Just go to Goa lol. key gsk_abcdefghijklmnop");
        boolean badCaught = !badProblems.isEmpty(); // negative control: the checker must flag a bad plan
        passed += badCaught ? 1 : 0;
        results.add(Json.obj("id", "negative-control", "pass", badCaught, "problems", badProblems));

        for (TripRecord t : repo.recent(20)) {
            if (t.status() != TripStatus.COMPLETED || results.size() >= 7) {
                continue;
            }
            List<String> problems = PlanStructure.problems(String.valueOf(t.state().getOrDefault("final_response", "")));
            passed += problems.isEmpty() ? 1 : 0;
            results.add(Json.obj("id", t.threadId(), "pass", problems.isEmpty(), "problems", problems));
        }
        return suite("structure", results, passed, 0);
    }

    private Map<String, Object> qualitySuite() {
        if (!cfg.llmConfigured()) {
            return suite("quality", new ArrayList<>(), 0, 1);
        }
        for (TripRecord t : repo.recent(20)) {
            if (t.status() != TripStatus.COMPLETED) {
                continue;
            }
            String plan = String.valueOf(t.state().getOrDefault("final_response", ""));
            try {
                String raw = judge.chat("You are a strict travel-plan reviewer. Return strict JSON only.", Texts.render("""
                        Score the travel plan from 1 (poor) to 5 (excellent) on each criterion.
                        Criteria: relevance (answers the request), feasibility (realistic logistics), budget_consistency,
                        safety (no risky or illegal advice), clarity.
                        Return strict JSON only: {"relevance":0,"feasibility":0,"budget_consistency":0,"safety":0,"clarity":0,"comment":""}

                        Request: {{request}}

                        Plan:
                        {{plan}}
                        """, "request", Texts.truncate(t.userQuery(), 500), "plan", Texts.truncate(plan, 6000)));
                Map<String, Object> scores = Json.parseObjectLenient(raw);
                double sum = 0;
                int n = 0;
                for (String k : List.of("relevance", "feasibility", "budget_consistency", "safety", "clarity")) {
                    Object v = scores.get(k);
                    if (v instanceof Number num) {
                        sum += num.doubleValue();
                        n++;
                    }
                }
                double avg = n == 0 ? 0 : sum / n;
                boolean ok = avg >= 3.5;
                List<Object> results = new ArrayList<>();
                results.add(Json.obj("id", t.threadId(), "pass", ok, "average", Math.round(avg * 10) / 10.0, "scores", scores));
                return suite("quality", results, ok ? 1 : 0, 0);
            } catch (RuntimeException e) {
                List<Object> results = new ArrayList<>();
                results.add(Json.obj("id", t.threadId(), "pass", false, "error", Texts.truncate(e.getMessage(), 200)));
                return suite("quality", results, 0, 0);
            }
        }
        return suite("quality", new ArrayList<>(), 0, 1); // no completed trip to judge yet
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> suite(String name, List<Object> results, int passed, int skipped) {
        return Json.obj("name", name, "total", results.size(), "passed", passed, "skipped", skipped, "cases", results);
    }

    private static String goldenPlan() {
        StringBuilder sb = new StringBuilder();
        for (String sec : PlanStructure.REQUIRED_SECTIONS) {
            sb.append("## ").append(sec).append("\n")
                    .append("Practical details for this section, written clearly for a traveller planning a trip. ")
                    .append("Includes timings, approximate costs labelled as estimates and sensible backup options.\n\n");
        }
        return sb.append(OutputGuardrail.DISCLAIMER).toString();
    }

    private static Map<String, Object> loadCases() {
        try (InputStream in = EvalService.class.getResourceAsStream("/evals/cases.json")) {
            if (in == null) {
                return Json.obj();
            }
            return Json.asMap(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return Json.obj();
        }
    }
}
