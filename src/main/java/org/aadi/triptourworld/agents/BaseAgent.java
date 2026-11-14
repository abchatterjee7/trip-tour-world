package org.aadi.triptourworld.agents;

import org.aadi.triptourworld.graph.TravelState;
import org.aadi.triptourworld.llm.ChatModel;
import org.aadi.triptourworld.util.Json;
import java.util.Map;

/** Shared helpers for agents. */
abstract class BaseAgent implements AgentNode {
    protected final ChatModel llm;

    BaseAgent(ChatModel llm) {
        this.llm = llm;
    }

    protected static Map<String, Object> up(Object... kv) {
        return Json.obj(kv);
    }

    protected static int calls(TravelState s) {
        return s.integer(TravelState.LLM_CALLS);
    }

    protected static String constraintText(TravelState s) {
        return Json.write(s.map(TravelState.CONSTRAINTS));
    }

    /** Wraps untrusted user text so the model treats it as data, not instructions. */
    protected static String userBlock(String query) {
        return "<user_request>\n" + query + "\n</user_request>";
    }
}
