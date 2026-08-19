package dev.ghbot.ai;

import java.util.List;

/**
 * Rule-based fallback — keeps GH-bot usable with NO AI backend at all.
 * Simple template responses; honest about being offline (P16 messaging).
 */
public class RuleBasedClient implements AIClient {

    @Override public String id() { return "fallback"; }
    @Override public String displayName() { return "Rule-based (no AI)"; }
    @Override public boolean isConfigured() { return true; }

    @Override
    public String chat(String systemPrompt, List<ChatMessage> history) throws Exception {
        // Last user message
        String user = "";
        for (ChatMessage m : history) if (m.role().equals("user")) user = m.content();
        String u = user.toLowerCase();

        if (u.contains("design") || u.contains("build") || u.contains("menu") || u.contains("menu")) {
            return "§7I'm running on my rule-based fallback (no AI backend connected). "
                    + "I can still help with commands — try @GH000 help. "
                    + "To get full design & chat, set an AI provider (Gemini key or Ollama).";
        }
        if (u.contains("help")) {
            return "§7I'm in fallback mode. Use @GH000 help to see commands. "
                    + "Connect Gemini (free tier) or Ollama for AI chat & design.";
        }
        if (u.contains("who are you") || u.contains("what are you")) {
            return "§7I'm GH000, your hands-off builder & admin bot. "
                    + "Right now I'm running without an AI brain (fallback mode).";
        }
        if (u.contains("what model") || u.contains("which model") || u.contains("who am i talking to")
                || u.contains("what ai") || u.contains("which ai")) {
            return "§7You're talking to my **rule-based fallback** — no AI provider is reachable "
                    + "right now (Gemini/Ollama/extras all failed or aren't configured). "
                    + "Run @GH000 provider list to see which are ✓, then connect one (see AI-KEYS-GUIDE.md) "
                    + "for full chat & design.";
        }
        if (u.contains("hi") || u.contains("hello") || u.contains("hey")) {
            return "§7Hey! GH000 here. Connect an AI provider for full chat — or use @GH000 help for commands.";
        }
        return "§7[GH000 fallback] I can't answer that without an AI provider. "
                + "Try @GH000 help, or connect Gemini/Ollama to unlock chat & design.";
    }
}
