package dev.ghbot.command;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * v0.22.2 — full-fidelity capture sender (Pillar 3, cmd output capture).
 *
 * Replaces the old String-only capture. On Paper 1.21, CommandSender extends
 * Adventure Audience, and EVERY message entry point funnels into terminal
 * default methods whose bodies are EMPTY — so a proxy that only overrides
 * sendMessage(String) silently drops all Component output (LuckPerms,
 * DeluxeMenus, vanilla feedback). This sender overrides all three messaging
 * surfaces:
 *   1. legacy String family (sendMessage(String)/String.../UUID variants)
 *   2. Adventure terminal sendMessage(Identity, Component, MessageType) —
 *      everything else (components, plain, rich, typed) funnels into this
 *   3. bungee legacy via a real CommandSender.Spigot (was null → NPE risk)
 *
 * Bounded: stops appending past MAX_CHARS and records how much was dropped.
 * Headless-smoke safe: nothing touches Bukkit at construction or capture time.
 */
public class CapturingSender implements CommandSender {

    /** Hard cap on captured text (chars). 8192 ≈ a few screens of console output. */
    public static final int MAX_CHARS = 8192;

    private final StringBuilder sb = new StringBuilder();
    private int dropped = 0;

    private void capture(String message) {
        if (message == null || message.isEmpty()) return;
        String clean = stripColor(message);
        if (clean.isEmpty()) return;
        for (String line : clean.split("\n", -1)) {
            if (line.isEmpty()) continue;
            append(line);
        }
    }

    private void append(String line) {
        if (sb.length() >= MAX_CHARS) { dropped += line.length() + 1; return; }
        int room = MAX_CHARS - sb.length();
        String piece = line.length() > room ? line.substring(0, Math.max(0, room)) : line;
        dropped += line.length() - piece.length();
        if (sb.length() > 0) sb.append('\n');
        sb.append(piece);
    }

    /** Number of characters dropped because the capture hit MAX_CHARS. */
    public int droppedChars() { return dropped; }

    // ── 1) legacy String surface ─────────────────────────────────────────────
    @Override public void sendMessage(String message) { capture(message); }
    @Override public void sendMessage(String... messages) { for (String m : messages) capture(m); }
    @Override public void sendMessage(UUID uuid, String message) { capture(message); }
    @Override public void sendMessage(UUID uuid, String... messages) { for (String m : messages) capture(m); }

    // ── 2) Adventure terminal (all Component paths funnel into this) ─────────
    @Override
    public void sendMessage(net.kyori.adventure.identity.Identity source,
                            net.kyori.adventure.text.Component message,
                            net.kyori.adventure.audience.MessageType type) {
        if (message == null) return;
        capture(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(message));
    }

    // ── 3) bungee legacy surface (was null — NPE for spigot().sendMessage callers) ──
    private final Spigot spigot = new Spigot() {
        @Override public void sendMessage(net.md_5.bungee.api.chat.BaseComponent component) {
            if (component != null) capture(component.toPlainText());
        }
        @Override public void sendMessage(net.md_5.bungee.api.chat.BaseComponent... components) {
            if (components == null) return;
            for (net.md_5.bungee.api.chat.BaseComponent c : components) sendMessage(c);
        }
        @Override public void sendMessage(UUID sender, net.md_5.bungee.api.chat.BaseComponent component) {
            sendMessage(component);
        }
        @Override public void sendMessage(UUID sender, net.md_5.bungee.api.chat.BaseComponent... components) {
            sendMessage(components);
        }
    };
    @Override public Spigot spigot() { return spigot; }

    // ── identity / permissions (act as a fully-trusted console-ish sender) ────
    @Override public Server getServer() { return org.bukkit.Bukkit.getServer(); }
    @Override public String getName() { return "GH-Bot capture"; }
    @Override public net.kyori.adventure.text.Component name() {
        return net.kyori.adventure.text.Component.text("GH-Bot capture");
    }
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

    /** Captured text (never null). Appends a truncation marker if content was dropped. */
    public String text() {
        String s = sb.toString().trim();
        return dropped > 0 ? s + "\n…(+" + dropped + " chars truncated — full output in logs/cmd/)" : s;
    }

    /** Raw captured text without the truncation marker (used when composing replies). */
    public String rawText() { return sb.toString().trim(); }

    /** Strip Minecraft chat-color codes (§x) — same behavior as the old capture. */
    public static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-orx]", "");
    }
}
