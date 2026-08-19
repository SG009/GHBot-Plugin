package dev.ghbot.ai;

import dev.ghbot.bot.GHBot;
import dev.ghbot.config.PluginConfig.AiConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds all configured AI providers. Resolution:
 *   bot.provider = "auto" → first *configured* provider in order gemini → ollama → openai → fallback
 *   bot.provider = "gemini"|"ollama"|"openai"|"fallback" → that one (fallback always resolves)
 */
public class ProviderRegistry {

    private final Map<String, AIClient> providers = new LinkedHashMap<>();
    private final RuleBasedClient fallback = new RuleBasedClient();

    public ProviderRegistry(AiConfig cfg) {
        rebuild(cfg);
    }

    /** v0.21.12 — rebuild providers from a fresh AiConfig (used by /gh reload). */
    public synchronized void reload(AiConfig cfg) {
        rebuild(cfg);
    }

    private void rebuild(AiConfig cfg) {
        providers.clear();
        providers.put("gemini", new GeminiClient(
                cfg.geminiEnabled(), cfg.geminiModel(), cfg.geminiApiKey(), cfg.geminiBaseUrl()));
        providers.put("ollama", new OllamaClient(
                cfg.ollamaEnabled(), cfg.ollamaModel(), cfg.ollamaBaseUrl()));
        providers.put("openai", new OpenAIClient(
                cfg.openaiEnabled(), cfg.openaiModel(), cfg.openaiApiKey(), cfg.openaiBaseUrl()));
        // v0.21.16 — extra OpenAI-compatible providers (Pollinations, Groq, Cerebras, Kiro-gateway, 9Router…)
        for (var ep : cfg.extraProviders()) {
            if (ep.id() == null || ep.id().isBlank()) continue;
            providers.put(ep.id().toLowerCase(), new OpenAIClient(
                    ep.enabled(), ep.model(), ep.apiKey(), ep.baseUrl(),
                    ep.id().toLowerCase(), ep.name()));
        }
        providers.put("fallback", fallback);
    }

    /** All configured providers in priority order (gemini → ollama → openai → extras), excluding fallback. */
    public java.util.List<AIClient> allConfigured() {
        java.util.List<AIClient> out = new java.util.ArrayList<>();
        for (AIClient c : providers.values()) {
            if (c != fallback && c.isConfigured()) out.add(c);
        }
        return out;
    }

    public AIClient get(String id) {
        return providers.get(id == null ? "" : id.toLowerCase());
    }

    public RuleBasedClient fallback() { return fallback; }

    /** Resolve the client for a bot (auto → first configured). Honors per-bot runtime override. */
    public AIClient resolve(GHBot bot) {
        String want = bot.config().provider() == null ? "auto" : bot.config().provider().toLowerCase();
        Object ov = bot.memory().get("provider");
        if (ov instanceof String s && !s.isBlank()) want = s.toLowerCase();
        if (!want.equals("auto")) {
            AIClient c = providers.get(want);
            if (c != null && c.isConfigured()) return c;
            if (want.equals("fallback")) return fallback;
            return fallback; // requested provider not configured → fallback
        }
        for (AIClient c : allConfigured()) return c;
        return fallback;
    }

    /** List of providers with status (for `provider list` / cap / device-info). */
    public java.util.List<String> statusLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (AIClient c : providers.values()) {
            out.add(c.id() + (c.isConfigured() ? " ✓" : " ✗"));
        }
        return out;
    }
}
