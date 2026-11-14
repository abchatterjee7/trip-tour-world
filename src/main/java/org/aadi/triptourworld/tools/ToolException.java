package org.aadi.triptourworld.tools;

/** A tool (flight / weather / search API) could not deliver; agents fall back to clearly-labelled general advice. */
public class ToolException extends RuntimeException {
    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
