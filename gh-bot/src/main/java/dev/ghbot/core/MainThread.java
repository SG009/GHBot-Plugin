package dev.ghbot.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * v0.21.9 — main-thread executor. Paper requires nearly all Bukkit API calls
 * (block placement, command dispatch, world reads) to run on the server's main
 * thread. The web HTTP thread, the async chat event thread, and GHBot's own
 * async pool are NOT the main thread, so every world-touching operation must
 * hop back via this helper. Falls back to running inline when there's no live
 * plugin (headless smoke tests) or we're already on the main thread.
 */
public final class MainThread {

    private static volatile JavaPlugin plugin;

    private MainThread() {}

    public static void setPlugin(JavaPlugin p) { plugin = p; }

    /** Run a callable on the main thread and return its result. */
    public static <T> T call(Callable<T> c) {
        if (plugin == null || Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            try { return c.call(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, c).get(90, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("main-thread hop failed: " + e.getMessage(), e);
        }
    }

    /** Run a task on the main thread (fire-and-forget). */
    public static void run(Runnable r) {
        call(() -> { r.run(); return null; });
    }
}
