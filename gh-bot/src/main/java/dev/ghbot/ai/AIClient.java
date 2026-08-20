package dev.ghbot.ai;

import java.util.List;

/**
 * Phase 5 — AI provider abstraction. "Nothing default": every backend is
 * optional & configurable; a rule-based fallback keeps things working with
 * no AI at all. HTTP uses the JDK built-in client (no deps).
 */
public interface AIClient {

    /** Chat message: role ∈ system / user / assistant. */
    record ChatMessage(String role, String content) {}

    /** Provider id ("gemini", "ollama", "openai", "fallback"). */
    String id();

    String displayName();

    /** True if the provider has what it needs (key / base url / enabled). */
    boolean isConfigured();

    /** Send a conversation, return the assistant's reply text. */
    String chat(String systemPrompt, List<ChatMessage> history) throws Exception;

    /** Stream a conversation, delivering text chunks to {@code onChunk}; returns the full reply. */
    default String stream(String systemPrompt, List<ChatMessage> history,
                          java.util.function.Consumer<String> onChunk) throws Exception {
        String full = chat(systemPrompt, history);
        if (onChunk != null && full != null) onChunk.accept(full);
        return full;
    }

    /** v0.21.40 — true if this provider accepts image input (vision). */
    default boolean supportsVision() { return false; }

    /**
     * v0.21.40 — send a user prompt together with an image (base64). Providers with
     * vision override this; the default says clearly that vision isn't supported so
     * callers can fall back to a vision-capable provider instead of failing silently.
     */
    default String chatWithImage(String systemPrompt, String userPrompt,
                                 String mimeType, byte[] imageBytes) throws Exception {
        throw new UnsupportedOperationException(
                id() + " does not support image input — enable a vision-capable provider (gemini or a multimodal ollama model)");
    }

    /**
     * v0.21.44 — cancel any in-flight HTTP request this client is currently blocked on.
     * Used by the web console's Stop button: aborting the request tells the AI provider
     * to stop generating (saves tokens/usage) instead of waiting out the timeout.
     * Default no-op; cancellable clients override it.
     */
    default void cancelActiveCall() {}
}
