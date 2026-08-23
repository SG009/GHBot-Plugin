package dev.ghbot.agent;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import org.bukkit.command.CommandSender;

import java.util.Set;

/**
 * v0.21.6 — Tool Bridge. Routes a tool call through a bot's own command
 * registry and captures the messages it would send, so the technician's AI
 * can invoke ANY existing bot command as a tool (plan, edit, schem, paste,
 * set, replace, terraform, where, workers, deploy, marker, critique, ...).
 * The captured text (with chat colors stripped) becomes the tool result.
 */
public final class ToolBridge {

    private ToolBridge() {}

    /**
     * A CommandSender that captures every message instead of showing it.
     * v0.22.2 — now a thin alias over dev.ghbot.command.CapturingSender: captures
     * legacy String, Adventure Component and bungee BaseComponent surfaces
     * (the old String-only version silently dropped Component output and
     * returned null from spigot() → NPE risk). Class name kept for callers.
     */
    public static final class Capture extends dev.ghbot.command.CapturingSender {}

    /** Strip Minecraft chat-color codes (§x). */
    public static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-orx]", "");
    }

    /**
     * Commands the AI may reach through the bridge (whitelist).
     * v0.21.14 — every command in the catalog is a tool (single source of truth);
     * v0.22.1 — removed the stale @Deprecated LEGACY_ALLOWED list (it still named
     * shelved commands and could drift from the catalog; ALLOWED is the only list).
     */
    public static final Set<String> ALLOWED = dev.ghbot.command.BotCommands.CATALOG.keySet();

    /** Run a whitelisted bot command and return its captured output. */
    public static String run(CommandBridge bridge, GHBot bot, String name, String[] args) {
        if (!ALLOWED.contains(name)) return "unknown or not-allowed tool: " + name;
        Capture c = new Capture();
        try {
            bridge.dispatch(bot, c, name, args);
        } catch (Throwable t) {
            return "command error: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
        return c.text().isBlank() ? "ok — no output" : c.text();
    }
}
