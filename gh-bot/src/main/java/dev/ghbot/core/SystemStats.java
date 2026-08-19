package dev.ghbot.core;

import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;

/**
 * Live system stats — the "system stats" part of your old core.js.
 * Sampled periodically by {@link StatsSampler} (safe when no Bukkit server
 * is present — smoke tests call sample() directly).
 */
public final class SystemStats {

    public volatile double tps = 20.0;
    public volatile double cpuPercent = 0.0;
    public volatile long usedMemMB = 0;
    public volatile long maxMemMB = 0;
    public volatile long totalMemMB = 0;
    public volatile long uptimeSec = 0;
    public volatile int players = 0;
    public volatile long lastSample = 0;

    public void sample() {
        Runtime rt = Runtime.getRuntime();
        maxMemMB = mb(rt.maxMemory());
        usedMemMB = mb(rt.totalMemory() - rt.freeMemory());
        totalMemMB = mb(osTotalMemory());
        cpuPercent = osCpuLoad();
        uptimeSec = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
        players = safePlayers();
        tps = safeTps();
        lastSample = System.currentTimeMillis();
    }

    public long freeMemMB() {
        return Math.max(0, maxMemMB - usedMemMB);
    }

    private static long mb(long bytes) {
        return bytes / 1048576L;
    }

    private static long osTotalMemory() {
        try {
            return ((com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean()).getTotalPhysicalMemorySize();
        } catch (Throwable t) {
            return Runtime.getRuntime().maxMemory();
        }
    }

    private static double osCpuLoad() {
        try {
            double load = ((com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean()).getProcessCpuLoad();
            return load < 0 ? 0.0 : load * 100.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static int safePlayers() {
        try { return Bukkit.getOnlinePlayers().size(); }
        catch (Throwable t) { return 0; }
    }

    /** Paper's TPS; falls back to 20.0 when no live server (e.g. smoke test). */
    private static double safeTps() {
        try {
            double[] tps = Bukkit.getTPS();
            return tps == null || tps.length == 0 ? 20.0 : tps[0];
        } catch (Throwable t) {
            return 20.0;
        }
    }
}
