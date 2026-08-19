package dev.ghbot.core;

import dev.ghbot.log.WIBLogger;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Periodic sampler (adapted from your old core.js block/entity scan + system
 * stats loops). Samples CPU/RAM/TPS/uptime every N ticks; when any bot has
 * debug logging on, also prints the classic debug line to console.
 */
public class StatsSampler extends BukkitRunnable {

    private final SystemStats stats = new SystemStats();
    private final WIBLogger log;
    private final java.util.function.BooleanSupplier anyDebug;

    public StatsSampler(WIBLogger log, java.util.function.BooleanSupplier anyDebug) {
        this.log = log;
        this.anyDebug = anyDebug;
    }

    public SystemStats stats() { return stats; }

    @Override
    public void run() {
        // Silent sampling — feeds /gh status, /gh device-info and @GH000 cap.
        // No periodic console output (was causing spam); reports are on-demand.
        stats.sample();
    }

    public static StatsSampler start(JavaPlugin plugin, WIBLogger log,
                                     int intervalTicks, java.util.function.BooleanSupplier anyDebug) {
        StatsSampler s = new StatsSampler(log, anyDebug);
        s.runTaskTimer(plugin, 20L, Math.max(20, intervalTicks));
        return s;
    }
}
