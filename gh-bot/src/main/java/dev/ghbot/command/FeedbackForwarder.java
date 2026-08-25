package dev.ghbot.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Constructor;
import java.util.function.Consumer;

/**
 * v0.22.3 — cmd dispatch route through Paper's FeedbackForwardingSender, so a
 * command's output comes back to GHBot instead of the server console.
 *
 * Root cause this fixes (live evidence, batch of 2026-08-25): on Paper 1.21,
 * CraftServer.dispatchCommand converts EVERY sender into a vanilla
 * CommandSourceStack via VanillaCommandWrapper.getListener(sender) BEFORE
 * parsing — and getListener throws IllegalArgumentException
 * ("Cannot make <sender> a vanilla command listener") for any plain custom
 * CommandSender. In v0.22.2 both dispatch attempts sat inside one try/catch,
 * so the throw from the custom-sender attempt also skipped the console
 * fallback → EVERY cmd reported "✗ failed", silently (plugins, version,
 * help, bukkit:plugins — even /gh).
 *
 * Paper ships the supported hook for exactly this in getListener:
 *   if (sender instanceof FeedbackForwardingSender f) return f.asVanilla();
 * FeedbackForwardingSender extends ServerCommandSender, carries OWNER-level
 * vanilla permission, and funnels ALL feedback — legacy String, Adventure
 * Component, vanilla sendSystemMessage — into one Consumer<Component> we
 * control. The class lives in paper-SERVER (not paper-api), so we reference
 * it by reflection only: no new compile dependency, headless smoke stays
 * green (class absent → "unavailable"), and Spigot/older Paper simply falls
 * back to console dispatch (the pre-1.21 working behavior).
 */
public final class FeedbackForwarder {

    /** io.papermc.paper.commands.FeedbackForwardingSender(Consumer<? super Component>, CraftServer). */
    private static final String CLASS_NAME = "io.papermc.paper.commands.FeedbackForwardingSender";
    private static final String CRAFTSERVER = "org.bukkit.craftbukkit.CraftServer";

    private static volatile int state = 0;              // 0 unchecked · 1 available · -1 unavailable
    private static Constructor<?> ctor;

    private FeedbackForwarder() {}

    /** True when this server's runtime carries FeedbackForwardingSender (Paper 1.21+). Cached. */
    public static boolean available() { return ctor() != null; }

    private static Constructor<?> ctor() {
        if (state == 0) {
            synchronized (FeedbackForwarder.class) {
                if (state == 0) {
                    Constructor<?> c = null;
                    try {
                        Class<?> ffs = Class.forName(CLASS_NAME);
                        Class<?> craft = Class.forName(CRAFTSERVER);
                        c = ffs.getConstructor(Consumer.class, craft);
                    } catch (Throwable ignored) {}
                    ctor = c;
                    state = c != null ? 1 : -1;
                }
            }
        }
        return ctor;
    }

    /**
     * Build a feedback-forwarding sender whose Consumer receives every reply
     * Component the command emits. Returns null when the runtime has no such
     * class (headless / Spigot / old Paper) — callers fall back to console
     * dispatch. Never throws.
     */
    public static CommandSender create(Consumer<net.kyori.adventure.text.Component> feedback) {
        Constructor<?> c = ctor();
        if (c == null) return null;
        try {
            Object server = Bukkit.getServer();
            if (server == null) return null;
            Object inst = c.newInstance(feedback, server);
            return inst instanceof CommandSender cs ? cs : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
