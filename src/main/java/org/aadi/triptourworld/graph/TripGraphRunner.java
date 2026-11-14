package org.aadi.triptourworld.graph;

import org.aadi.triptourworld.agents.Agents;
import org.aadi.triptourworld.config.Settings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

/**
 * Runs the compiled graph for one thread: {@link #start} executes until the human-approval interrupt (or the
 * end), {@link #resume} writes the reviewer's decision into the checkpointed state and continues.
 */
public final class TripGraphRunner {
    private final CompiledGraph<TravelState> graph;
    private final MemorySaver saver;

    public TripGraphRunner(Agents agents, Settings settings) throws GraphStateException {
        this.saver = new MemorySaver();
        this.graph = TripGraphFactory.build(agents, saver, settings);
    }

    /** Runs from START; returns the last state seen (draft ready, blocked, or completed). */
    public TravelState start(String threadId, Map<String, Object> input, Consumer<String> onNode) {
        RunnableConfig cfg = config(threadId);
        return consume(graph.stream(input, cfg), onNode);
    }

    /** Applies the human decision to the paused thread and continues to the next stop. */
    public TravelState resume(String threadId, boolean approved, String feedback,
                              Map<String, Object> extraState, Consumer<String> onNode) throws Exception {
        RunnableConfig cfg = config(threadId);
        Map<String, Object> update = new LinkedHashMap<>(extraState);
        update.put(TravelState.APPROVED, approved);
        update.put(TravelState.HUMAN_FEEDBACK, feedback == null ? "" : feedback);
        RunnableConfig updated = graph.updateState(cfg, update);
        return consume(graph.stream(GraphInput.resume(), updated), onNode);
    }

    /** Frees the in-memory checkpoints of a finished thread. */
    public void release(String threadId) {
        try {
            saver.release(config(threadId));
        } catch (Exception ignored) {
            // nothing to release
        }
    }

    private static RunnableConfig config(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    private static TravelState consume(Iterable<NodeOutput<TravelState>> stream, Consumer<String> onNode) {
        TravelState last = null;
        for (NodeOutput<TravelState> out : stream) {
            String node = out.node();
            if (!StateGraph.START.equals(node) && !StateGraph.END.equals(node)) {
                onNode.accept(node);
            }
            last = out.state();
        }
        if (last == null) {
            throw new IllegalStateException("The graph produced no output");
        }
        return last;
    }
}
