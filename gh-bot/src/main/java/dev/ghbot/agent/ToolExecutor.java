package dev.ghbot.agent;

import java.util.function.BiFunction;

/**
 * Executes tool calls for the chat agent. The plugin supplies a runner
 * (name, args → result text) wired to its services.
 */
public final class ToolExecutor {

    private final BiFunction<String, String[], String> runner;

    public ToolExecutor(BiFunction<String, String[], String> runner) {
        this.runner = runner;
    }

    /** Run one tool call. Returns a result line, or an error line. */
    public String run(ToolProtocol.ToolCall call) {
        if (runner == null) return "Tool runner not available.";
        try {
            String result = runner.apply(call.name(), call.args());
            return result == null || result.isBlank() ? "Tool '" + call.name() + "' returned nothing."
                    : "⟦result:" + call.name() + "⟧ " + result;
        } catch (Throwable t) {
            return "⟦result:" + call.name() + "⟧ ERROR: " + t.getMessage();
        }
    }
}
