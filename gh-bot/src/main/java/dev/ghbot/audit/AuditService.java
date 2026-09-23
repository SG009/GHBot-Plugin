package dev.ghbot.audit;

import dev.ghbot.log.WIBLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.24.0 — Phase B (owner's re-scope: "GHBot audits the console FOR me — errors,
 * warnings, plugin/Paper updates, with suggestions. NOT a second console.").
 *
 * Glues together {@link LogWatch} (WARN+ ring) and {@link UpdateRadar}; turns the
 * ring into a per-plugin digest with one practical hint per group. The listener
 * attaches reflectively (LogWatch) — when unavailable, audit degrades to
 * radar-only with a note. Nothing is ever auto-spammed: answers come from the
 * `audit` command; the radar prints at most ONE console line per boot when
 * something is behind.
 */
public final class AuditService {

    private final JavaPlugin plugin;
    private final WIBLogger log;
    private final LogWatch watch = new LogWatch(LogWatch.DEFAULT_CAPACITY);
    private final java.nio.file.Path notifiedFile;
    private volatile Map<String, String> pluginRoots = Map.of();   // package-root → plugin name
    private volatile List<UpdateRadar.CheckResult> radarResults;   // null = first pass pending
    private volatile long bootTime = System.currentTimeMillis();
    private final java.util.List<Integer> taskIds = new java.util.ArrayList<>();

    public AuditService(JavaPlugin plugin, WIBLogger log) {
        this.plugin = plugin;
        this.log = log;
        this.notifiedFile = plugin.getDataFolder().toPath().resolve("updates-notified.yml");
    }

    public LogWatch watch() { return watch; }
    public List<UpdateRadar.CheckResult> radarResults() { return radarResults; }

    /** Called at enable: attach the listener + harvest plugin roots + schedule radar. */
    public void start() {
        watch.attach(log);
        Map<String, String> roots = new LinkedHashMap<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            String pkg = p.getClass().getPackageName();
            String[] seg = pkg.split("\\.");
            String root = seg.length >= 2 ? seg[0] + "." + seg[1] : pkg;
            roots.put(root, p.getName());
        }
        pluginRoots = roots;
        // first radar pass after 60 s (let the network/MC settle), then daily
        scheduleOnce(60L * 20L);
        scheduleDaily();
    }

    public void stop() {
        for (int id : taskIds) {
            try { Bukkit.getScheduler().cancelTask(id); } catch (Throwable ignored) {}
        }
        taskIds.clear();
        watch.close();
    }

    /** Radar pass on the async pool (never the main thread — it's HTTP). */
    public void refreshUpdatesAsync(Runnable onDoneInOrder) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            runRadarPass();
            if (onDoneInOrder != null) onDoneInOrder.run();
        });
    }

    private void scheduleOnce(long delayTicks) {
        taskIds.add(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                this::runRadarPass, delayTicks).getTaskId());
    }

    private void scheduleDaily() {
        taskIds.add(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                this::runRadarPass, 20L * 60 * 60 * 24, 20L * 60 * 60 * 24).getTaskId());
    }

    private void runRadarPass() {
        try {
            Map<String, String> installed = new LinkedHashMap<>();
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                installed.put(p.getName(), p.getDescription().getVersion());
            }
            int paperBuild = paperBuild();
            String mc = Bukkit.getMinecraftVersion();
            List<UpdateRadar.CheckResult> results = UpdateRadar.runOnce(installed, paperBuild, mc);
            radarResults = results;
            Map<String, String> prev = UpdateRadar.loadNotified(notifiedFile);
            List<UpdateRadar.CheckResult> fresh = UpdateRadar.freshNotices(results, prev);
            if (!fresh.isEmpty()) {
                for (UpdateRadar.CheckResult r : fresh) prev.put(r.pluginName(), r.latest());
                UpdateRadar.saveNotified(notifiedFile, prev);
                StringBuilder sb = new StringBuilder("update radar: ");
                boolean f = true;
                for (UpdateRadar.CheckResult r : fresh) {
                    if (!f) sb.append(" · ");
                    f = false;
                    sb.append(r.pluginName()).append(' ').append(r.current()).append(" → ").append(r.latest());
                }
                sb.append(" — ask GHBot 'audit updates'");
                log.info(sb.toString());
            }
        } catch (Throwable t) {
            log.warn("[GHBot] update radar pass failed: " + t.getClass().getSimpleName()
                    + " — will retry on schedule");
        }
    }

    /** Paper build int from Bukkit.getVersion() ("…1.21.11-132-… (MC: 1.21.11)"), 0 when unparseable. */
    public static int paperBuild() {
        try {
            String v = Bukkit.getVersion();
            var m = java.util.regex.Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)-(\\d+)-").matcher(v);
            return m.find() ? Integer.parseInt(m.group(2)) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Built-in plumbing check: emits a synthetic WARN through the plugin logger;
     *  a working listener then surfaces it in the digest attributed to GHBot. */
    public String selfTest() {
        String line = "audit self-test warning (listener plumbing check — safe to ignore)";
        log.warn(line);
        return line;
    }

    public void clear() { watch.clear(); }

    /* ── attribution + suggestions (headless-pinned statics) ─────────── */

    /** Map a console line to its plugin: logger prefix first, stack frames as
     *  fallback; server core → "server". */
    public static String attribute(String logger, String thrown, Map<String, String> pluginRoots) {
        String best = null;
        int bestLen = 0;
        for (var e : pluginRoots.entrySet()) {
            if (logger != null && logger.startsWith(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        if (best != null) return best;
        if (thrown != null && !thrown.isBlank()) {
            for (var e : pluginRoots.entrySet()) {
                if (thrown.contains(e.getKey() + ".")) return e.getValue();
            }
        }
        if (logger != null && (logger.startsWith("net.minecraft") || logger.startsWith("com.mojang")
                || logger.startsWith("org.bukkit") || logger.startsWith("io.papermc")
                || logger.startsWith("ca.spottedleaf") || logger.equals("Minecraft")
                || logger.startsWith("Minecraft.")
                || logger.equals("PaperMC") || logger.equals("Server") || logger.equals("netty-io"))) {
            return "server";
        }
        return logger == null || logger.isBlank() ? "?" : logger;
    }

    private static final List<String[]> RULES = List.of(
            new String[]{"(?i)NoClassDefFoundError|ClassNotFoundException",
                    "%s can't find a class — wrong build for this server or a missing dependency; reinstall %s for this MC version"},
            new String[]{"(?i)UnsupportedClassVersionError",
                    "%s was compiled for a NEWER Java than this server runs — update Java or get an older %s build"},
            new String[]{"(?i)Error occurred while enabling|Failed to (load|enable)|Could not load",
                    "%s failed to start — check its config and the stack above in latest.log"},
            new String[]{"(?i)deprecated|deprecation",
                    "deprecated-API warning — safe short-term; a future %s update will silence it"},
            new String[]{"(?i)release\\(s\\) behind|newer (stable )?release|update available|new build available",
                    "%s reports a newer release — schedule an update when convenient; the radar line tracks it"},
            new String[]{"(?i)HTTP 401|UNAUTHORIZED|API key is required|invalid api[- ]key",
                    "the AI provider rejected the key — set a valid api-key under ai: in plugins/GHBot/config.yml or switch provider"},
            new String[]{"(?i)out of memory|OutOfMemoryError",
                    "server is out of RAM — raise -Xmx or lower view-distance/simulation-distance"},
            new String[]{"(?i)connection reset|read timed out|connect timed out|Timed out",
                    "network blip — if it repeats, check host firewall/proxy/DNS"},
            new String[]{"(?i)ssl|certificate|handshake",
                    "TLS/handshake problem — often wrong system time or old CA certs on the host"},
            new String[]{"(?i)exception|error",
                    "read the stack in latest.log; if it survives a restart, report it to %s with that log"});

    /** One practical hint per group (2× %s = plugin name). */
    public static String suggest(String plugin, String level, String message, String thrown) {
        String hay = (message == null ? "" : message) + "\n" + (thrown == null ? "" : thrown);
        for (String[] rule : RULES) {
            if (java.util.regex.Pattern.compile(rule[0]).matcher(hay).find()) {
                return rule[1].formatted(plugin, plugin);
            }
        }
        return "usually harmless unless spammy — watch if it repeats or correlates with problems";
    }

    /* ── the digest ───────────────────────────────────────────────────── */

    /** Grouped, severity-first, bounded chat digest. Never throws. */
    public String digest() {
        return buildDigest(watch.snapshot(), pluginRoots, radarResults, bootTime);
    }

    /** Static seam (smoke-pinned): the instance version feeds live state in. */
    public static String buildDigest(List<LogWatch.Entry> entries, Map<String, String> pluginRoots,
                                     List<UpdateRadar.CheckResult> radarResults, long bootTime) {
        StringBuilder sb = new StringBuilder();
        if (entries.isEmpty()) {
            sb.append("✅ server audit: clean — no WARN/ERROR captured since boot.");
        } else {
            int errors = 0, warns = 0, total = 0;
            Map<String, List<LogWatch.Entry>> byPlugin = new LinkedHashMap<>();
            for (LogWatch.Entry e : entries) {
                total += e.count;
                String src = attribute(e.logger, e.thrown, pluginRoots);
                byPlugin.computeIfAbsent(src, k -> new ArrayList<>()).add(e);
            }
            // severity-first (groups containing any ERROR/FATAL first), then by total count desc
            List<Map.Entry<String, List<LogWatch.Entry>>> groups = new ArrayList<>(byPlugin.entrySet());
            groups.sort((g1, g2) -> {
                int s1 = severity(g1.getValue()), s2 = severity(g2.getValue());
                if (s1 != s2) return Integer.compare(s2, s1);
                return Integer.compare(countOf(g2.getValue()), countOf(g1.getValue()));
            });
            for (LogWatch.Entry e : entries) {
                if (e.level.equals("ERROR") || e.level.equals("FATAL")) errors += e.count; else warns += e.count;
            }
            // truthful age: real boots pass bootTime>0; a 0/negative stamps "since boot"
            // rather than an absurd minute count
            String age = bootTime > 0
                    ? "last " + Math.max(0, (System.currentTimeMillis() - bootTime) / 60000) + " min"
                    : "since boot";
            sb.append("📋 server audit — ").append(total).append(" event(s) · ")
              .append(byPlugin.size()).append(" source(s) · errors: ").append(errors)
              .append(", warnings: ").append(warns).append(" · ").append(age).append(":");
            int shown = 0;
            for (var g : groups) {
                if (shown >= 6) { sb.append("\n… +").append(groups.size() - 6).append(" more source(s)"); break; }
                shown++;
                LogWatch.Entry rep = representative(g.getValue());
                int n = countOf(g.getValue());
                int distinct = distinctMessages(g.getValue());
                String lvl = severity(g.getValue()) >= 2 ? "ERROR" : "WARN";
                String msg = rep.message.length() > 90 ? rep.message.substring(0, 90) + "…" : rep.message;
                // the hint scans ALL lines of the group, not just the quoted one —
                // banners/stacks often carry the actionable bit on another line
                String hint = suggest(g.getKey(), lvl, groupHay(g.getValue()), "");
                if (distinct == 1) {
                    // every line identical → the "×N" after the quote is truthful
                    sb.append("\n• ").append(g.getKey()).append(" (").append(lvl)
                      .append(n > 1 ? " ×" + n : "").append("): \"").append(msg)
                      .append(n > 1 ? "\" ×" + n : "\"").append(" — ").append(hint);
                } else {
                    // mixed lines (e.g. Paper's multi-line update banner): quote the most
                    // informative line, state the true line count — never imply "×N"
                    sb.append("\n• ").append(g.getKey()).append(" (").append(lvl)
                      .append(" ×").append(n).append(", ").append(distinct).append(" lines): \"")
                      .append(msg).append("\" — ").append(hint);
                }
            }
        }
        sb.append("\n").append(UpdateRadar.line(radarResults));
        return sb.toString();
    }

    private static int severity(List<LogWatch.Entry> es) {
        int s = 0;
        for (LogWatch.Entry e : es) {
            if (e.level.equals("ERROR") || e.level.equals("FATAL")) s = 2;
            else if (s == 0) s = 1;
        }
        return s;
    }

    private static int countOf(List<LogWatch.Entry> es) {
        int n = 0;
        for (LogWatch.Entry e : es) n += e.count;
        return n;
    }

    /** Borders/banner frames ("*****", "=====") carry no information — skip them. */
    static boolean borderOnly(String s) {
        return s == null || s.matches("[\\s*=#_\\-~]*");
    }

    /** The line that best tells the admin WHAT this group is: longest non-border
     *  message; newest wins ties (and when every line is a border, the newest). */
    static LogWatch.Entry representative(List<LogWatch.Entry> es) {
        LogWatch.Entry best = null;
        for (LogWatch.Entry e : es) {
            if (borderOnly(e.message)) continue;
            if (best == null || e.message.length() >= best.message.length()) best = e;
        }
        return best == null ? es.get(es.size() - 1) : best;
    }

    static int distinctMessages(List<LogWatch.Entry> es) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (LogWatch.Entry e : es) seen.add(e.message);
        return seen.size();
    }

    /** All lines + stacks of a group concatenated — the suggestion-rule haystack. */
    static String groupHay(List<LogWatch.Entry> es) {
        StringBuilder sb = new StringBuilder();
        for (LogWatch.Entry e : es) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.message);
            if (!e.thrown.isEmpty()) sb.append('\n').append(e.thrown);
            if (sb.length() > 4000) break;
        }
        return sb.toString();
    }

    /** Details for `audit updates`: one line per checked plugin. */
    public String updatesDetail() {
        List<UpdateRadar.CheckResult> rs = radarResults;
        if (rs == null) return "update radar: first check still pending (runs ~60 s after boot) — try again shortly.";
        StringBuilder sb = new StringBuilder("update radar detail:");
        for (UpdateRadar.CheckResult r : rs) {
            sb.append("\n• ").append(r.pluginName()).append(" — installed ").append(r.current())
              .append(r.status() == UpdateRadar.Status.BEHIND
                      ? " · LATEST " + r.latest() + " ⬆ (behind!)"
                      : r.status() == UpdateRadar.Status.CURRENT ? " · current ✓"
                      : r.status() == UpdateRadar.Status.AHEAD ? " · ahead of public release (!)"
                      : r.status() == UpdateRadar.Status.OFFLINE ? " · check failed (offline)"
                      : " · latest unknown");
        }
        return sb.toString();
    }
}
