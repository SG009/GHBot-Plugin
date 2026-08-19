package dev.ghbot.core;

import org.bukkit.Bukkit;

import java.util.List;

/**
 * P16 — Capability Estimator. Turns live system stats into GH-bot's honest
 * "what can this device do" report, plus the additive "what more you'd get
 * on better hardware" messaging. "Low spec? No problem. High spec? GIGA-CHAD."
 * v0.21: added tier 4 (GIGA-CHAD server) — the plan scales up, never down.
 */
public final class CapabilityEstimator {

    public static final int TIER_PHONE = 1;
    public static final int TIER_MID = 2;
    public static final int TIER_STRONG = 3;
    public static final int TIER_GIGA = 4;

    public record Report(
            int tier,
            String tierName,
            long freeMemMB,
            long totalMemMB,
            double cpuPercent,
            double tps,
            long uptimeSec,
            int estMaxBuildBlocks,
            String offlineModel,
            int parallelBots,
            boolean fawePresent,
            List<String> providers,
            String headline
    ) {}

    public static Report build(SystemStats s, List<String> configuredProviders) {
        long totalGB = s.totalMemMB / 1024L;
        int tier;
        if (totalGB < 8) tier = TIER_PHONE;
        else if (totalGB < 16) tier = TIER_MID;
        else if (totalGB < 32) tier = TIER_STRONG;
        else tier = TIER_GIGA;

        String name = switch (tier) {
            case TIER_PHONE -> "phone (6–8 GB)";
            case TIER_MID -> "mid PC (8–16 GB)";
            case TIER_STRONG -> "strong PC / GPU (16–32 GB)";
            default -> "GIGA-CHAD server (32 GB+)";
        };

        // rough additive power per tier (blocks per job, parallel bots, local model)
        int maxBlocks = switch (tier) {
            case TIER_PHONE -> Math.max(500, (int) (s.freeMemMB() * 30L));
            case TIER_MID -> Math.max(3000, (int) (s.freeMemMB() * 60L));
            case TIER_STRONG -> Math.max(20000, (int) (s.freeMemMB() * 100L));
            default -> Math.max(100000, (int) (s.freeMemMB() * 150L));
        };
        int parallelBots = switch (tier) {
            case TIER_PHONE -> 1;
            case TIER_MID -> 3;
            case TIER_STRONG -> 6;
            default -> 10;
        };
        String offlineModel = switch (tier) {
            case TIER_PHONE -> "Ollama 0.8B (tight but doable)";
            case TIER_MID -> "Ollama 3–4B comfortably";
            case TIER_STRONG -> "Ollama 7B–32B / paid cloud";
            default -> "Ollama 30B+ / cloud with full context";
        };
        String headline = switch (tier) {
            case TIER_PHONE -> "full feature set — builds capped for TPS safety";
            case TIER_MID -> "additive: 3-bot crews, bigger jobs, 3–4B local AI";
            case TIER_STRONG -> "additive: 6-bot crews, 20k+ block jobs, 7–32B local AI";
            default -> "GIGA-CHAD: 10-bot crews, 100k+ block jobs, big local AI, FAWE-class mega-paste";
        };

        boolean fawe = false;
        try {
            fawe = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null
                    || Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
        } catch (Throwable ignored) {}

        return new Report(tier, name, s.freeMemMB(), s.totalMemMB,
                s.cpuPercent, s.tps, s.uptimeSec, maxBlocks, offlineModel,
                parallelBots, fawe, configuredProviders, headline);
    }

    /** Full text report for the `cap` command. */
    public static String reportText(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("§e[GH-Bot] Capability report — tier ").append(r.tier())
          .append(" (§f").append(r.tierName()).append("§e)");
        sb.append("\n§f  RAM: §a").append(r.freeMemMB()).append(" MB free")
          .append(" §7/ ").append(r.totalMemMB()).append(" MB total");
        sb.append("\n§f  TPS: §a").append(String.format("%.1f", r.tps()))
          .append("§f  · CPU: §a").append(String.format("%.1f%%", r.cpuPercent()));
        sb.append("\n§f  Uptime: §7").append(formatUptime(r.uptimeSec()));
        sb.append("\n§f  Est. max per job: §a").append(r.estMaxBuildBlocks()).append(" blocks");
        sb.append("\n§f  Parallel bots: §a").append(r.parallelBots())
          .append("§f  · Offline AI: §a").append(r.offlineModel());
        if (r.fawePresent()) sb.append("\n§f  FAWE/WorldEdit: §aavailable §7(fast mega-paste)");
        sb.append("\n§f  §7").append(r.headline());
        sb.append("\n§7  With better hardware: more bots, bigger builds, bigger local AI. "
                + "Low spec? No problem. High spec? GIGA-CHAD.");
        return sb.toString();
    }

    /** Short one-line capability notice (throttled P16 messages). */
    public static String noticeLine(Report r) {
        if (r.tier() == TIER_PHONE) {
            return "§7Heads up: running on " + r.tierName()
                    + ". I can build up to ~" + r.estMaxBuildBlocks()
                    + " blocks/job fine. More RAM = more bots + mega builds.";
        }
        if (r.tier() == TIER_GIGA) {
            return "§7GIGA-CHAD device tier " + r.tier() + " (" + r.tierName()
                    + ") — " + r.parallelBots() + " parallel bots, ~"
                    + r.estMaxBuildBlocks() + " blocks/job. Full crew + mega builds unlocked.";
        }
        return "§7Device tier " + r.tier() + " (" + r.tierName()
                + ") — " + r.parallelBots() + " parallel bots, ~"
                + r.estMaxBuildBlocks() + " blocks/job.";
    }

    private static String formatUptime(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h + "h " + m + "m " + s + "s";
    }
}
