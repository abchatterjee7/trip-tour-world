package org.aadi.triptourworld.llm;

/** Minimal LLM port. The production adapter is {@link GroqChatModel}; tests use scripted fakes. */
public interface ChatModel {

    /** Sends one system + one user message and returns the assistant's text. */
    String chat(String system, String user);

    /** Human-readable model label for health/diagnostics. */
    String name();
}
