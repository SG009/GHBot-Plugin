package dev.ghbot.agent;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * v0.21.6 — Tool Bridge. Routes a tool call through a bot's own command
 * registry and captures the messages it would send, so the technician's AI
 * can invoke ANY existing bot command as a tool (plan, edit, schem, paste,
 * set, replace, terraform, where, workers, deploy, marker, critique, ...).
 * The captured text (with chat colors stripped) becomes the tool result.
 */
public final class ToolBridge {

    private ToolBridge() {}

    /** A CommandSender that captures every message instead of showing it. */
    public static final class Capture implements CommandSender {
        private final StringBuilder sb = new StringBuilder();

        @Override public void sendMessage(String message) {
            if (message == null || message.isEmpty()) return;
            String clean = stripColor(message);
            if (sb.length() > 0) sb.append('\n');
            sb.append(clean);
        }
        @Override public void sendMessage(String... messages) { for (String m : messages) sendMessage(m); }
        @Override public void sendMessage(UUID uuid, String message) { sendMessage(message); }
        @Override public void sendMessage(UUID uuid, String... messages) { for (String m : messages) sendMessage(m); }
        @Override public Server getServer() { return org.bukkit.Bukkit.getServer(); }
        @Override public String getName() { return "GH-Bot tool"; }
        @Override public net.kyori.adventure.text.Component name() {
            return net.kyori.adventure.text.Component.text("GH-Bot tool");
        }
        @Override public Spigot spigot() { return null; }
        @Override public boolean isPermissionSet(String name) { return true; }
        @Override public boolean isPermissionSet(Permission perm) { return true; }
        @Override public boolean hasPermission(String name) { return true; }
        @Override public boolean hasPermission(Permission perm) { return true; }
        @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin plugin) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin plugin, int ticks) { return null; }
        @Override public void removeAttachment(PermissionAttachment attachment) {}
        @Override public void recalculatePermissions() {}
        @Override public Set<PermissionAttachmentInfo> getEffectivePermissions() { return java.util.Collections.emptySet(); }
        @Override public boolean isOp() { return true; }
        @Override public void setOp(boolean value) {}
        public UUID getUniqueId() { return UUID.randomUUID(); }

        public String text() { return sb.toString().trim(); }
    }

    /** Strip Minecraft chat-color codes (§x). */
    public static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-orx]", "");
    }

    /** Commands the AI may reach through the bridge (whitelist). */
    /** v0.21.14 — every command in the catalog is a tool (single source of truth). */
    public static final Set<String> ALLOWED = dev.ghbot.command.BotCommands.CATALOG.keySet();

    @Deprecated
    public static final Set<String> LEGACY_ALLOWED = Set.of(
            "plan", "edit", "editspec", "schem", "paste", "library",
            "set", "replace", "terraform",
            "where", "list-locations", "save-location", "delete-location",
            "marker", "avatar", "animate", "style",
            "workers", "deploy", "undeploy",
            "critique", "provider", "refresh", "add", "confirm",
            "teach", "dataset", "export", "cancel", "memory", "image", "help",
            "approve", "deny", "redo"   // v0.21.13 — review actions from web chat
    );

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
