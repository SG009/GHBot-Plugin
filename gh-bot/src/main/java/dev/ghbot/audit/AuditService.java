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
    private volatile long radarStamp;                              // when the last pass succeeded (0 = never)
    private volatile FixRules fixes = FixRules.fromString(null);   // v0.26.0 — fix knowledge base (built-ins floor)
    private volatile java.io.File fixesFile;                       // v0.26.0 — audit-fixes.yml (for `audit reload`)
    private volatile long bootTime = System.currentTimeMillis();
    private final java.util.List<Integer> taskIds = new java.util.ArrayList<>();

    public AuditService(JavaPlugin plugin, WIBLogger log) {
        this.plugin = plugin;
        this.log = log;
        this.notifiedFile = plugin.getDataFolder().toPath().resolve("updates-notified.yml");
    }

    public LogWatch watch() { return watch; }
    public List<UpdateRadar.CheckResult> radarResults() { return radarResults; }
    public long radarStamp() { return radarStamp; }
    public FixRules fixes() { return fixes; }

    /** v0.26.0 — load/reload the fix knowledge base (audit-fixes.yml + built-in floor). Never throws. */
    public void loadFixes(java.io.File f) {
        fixesFile = f;
        FixRules fr = FixRules.fromString(null);
        if (f != null && f.isFile()) {
            try { fr = FixRules.fromString(java.nio.file.Files.readString(f.toPath())); }
            catch (Throwable t) { log.warn("[GHBot] audit-fixes.yml unreadable (" + t.getClass().getSimpleName() + ") — built-in rules only"); }
        }
        fixes = fr;
        log.info("[GHBot] audit fix rules: " + fr.custom().size() + " file + " + fr.builtinCount() + " built-ins");
    }

    /** v0.26.0 — `audit reload`: re-read audit-fixes.yml without a restart. */
    public String reloadFixes() {
        loadFixes(fixesFile);
        return "audit-fixes.yml reloaded — " + fixes.custom().size() + " file rule(s) + " + fixes.builtinCount() + " built-ins";
    }

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
            radarStamp = System.currentTimeMillis();
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

    /**
     * THE group ordering — severity-first (groups containing ERROR/FATAL first),
     * then total count desc. Shared by the digest, `audit show <n>` and
     * `audit fix <n>` so a group's NUMBER can never mean different things on
     * different surfaces (same lesson as toolScanReply, v0.25.0).
     */
    public static List<Map.Entry<String, List<LogWatch.Entry>>> orderedGroups(
            List<LogWatch.Entry> entries, Map<String, String> pluginRoots) {
        Map<String, List<LogWatch.Entry>> byPlugin = new LinkedHashMap<>();
        for (LogWatch.Entry e : entries) {
            byPlugin.computeIfAbsent(attribute(e.logger, e.thrown, pluginRoots), k -> new ArrayList<>()).add(e);
        }
        List<Map.Entry<String, List<LogWatch.Entry>>> groups = new ArrayList<>(byPlugin.entrySet());
        groups.sort((g1, g2) -> {
            int s1 = severity(g1.getValue()), s2 = severity(g2.getValue());
            if (s1 != s2) return Integer.compare(s2, s1);
            return Integer.compare(countOf(g2.getValue()), countOf(g1.getValue()));
        });
        return groups;
    }

    /** Grouped, severity-first, bounded chat digest. Never throws. */
    public String digest() {
        return buildDigest(watch.snapshot(), pluginRoots, radarResults, bootTime);
    }

    /** Static seam (smoke-pinned): the instance version feeds live state in. */
    public static String buildDigest(List<LogWatch.Entry> entries, Map<String, String> pluginRoots,
                                     List<UpdateRadar.CheckResult> radarResults, long bootTime) {
        StringBuilder sb = new StringBuilder();
        List<Map.Entry<String, List<LogWatch.Entry>>> groups = orderedGroups(entries, pluginRoots);
        if (groups.isEmpty()) {
            sb.append("✅ server audit: clean — no WARN/ERROR captured since boot.");
        } else {
            int errors = 0, warns = 0, total = 0;
            for (LogWatch.Entry e : entries) {
                total += e.count;
                if (e.level.equals("ERROR") || e.level.equals("FATAL")) errors += e.count; else warns += e.count;
            }
            // truthful age: real boots pass bootTime>0; a 0/negative stamps "since boot"
            // rather than an absurd minute count
            String age = bootTime > 0
                    ? "last " + Math.max(0, (System.currentTimeMillis() - bootTime) / 60000) + " min"
                    : "since boot";
            sb.append("📋 server audit — ").append(total).append(" event(s) · ")
              .append(groups.size()).append(" source(s) · errors: ").append(errors)
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
                    // v0.26.0 — numbered groups feed `audit show <n>` / `audit fix <n>` (same ordering)
                    sb.append("\n• ").append(shown).append(") ").append(g.getKey()).append(" (").append(lvl)
                      .append(n > 1 ? " ×" + n : "").append("): \"").append(msg)
                      .append(n > 1 ? "\" ×" + n : "\"").append(" — ").append(hint);
                } else {
                    // mixed lines (e.g. Paper's multi-line update banner): quote the most
                    // informative line, state the true line count — never imply "×N"
                    sb.append("\n• ").append(shown).append(") ").append(g.getKey()).append(" (").append(lvl)
                      .append(" ×").append(n).append(", ").append(distinct).append(" lines): \"")
                      .append(msg).append("\" — ").append(hint);
                }
            }
        }
        sb.append("\n").append(UpdateRadar.line(radarResults));
        if (!groups.isEmpty()) {
            sb.append("\n🔍 browse full lines: audit show 1..").append(groups.size())
              .append(" · 🛠 fix advice: audit fix <n>");
        }
        return sb.toString();
    }

    /* ── v0.26.0 — Phase E2: browse (`audit show <n>`) + fix advice (`audit fix <n>`) ── */

    /** Full drill-down of group #n (digest ordering): every captured line, counts,
     *  stacks — bounded to 25 rendered lines with a truthful trim note. */
    public String show(int n) { return showGroup(watch.snapshot(), pluginRoots, n); }

    /** Static seam (smoke-pinned). */
    public static String showGroup(List<LogWatch.Entry> entries, Map<String, String> pluginRoots, int n) {
        List<Map.Entry<String, List<LogWatch.Entry>>> groups = orderedGroups(entries, pluginRoots);
        if (n < 1 || n > groups.size()) {
            return "no such audit source #" + n + (groups.isEmpty()
                    ? " — the audit ring is empty (nothing captured yet)"
                    : " — valid: 1.." + groups.size());
        }
        var g = groups.get(n - 1);
        int errs = 0, warns = 0;
        long newest = 0;
        for (LogWatch.Entry e : g.getValue()) {
            if (e.level.equals("ERROR") || e.level.equals("FATAL")) errs += e.count; else warns += e.count;
            newest = Math.max(newest, e.lastTime);
        }
        StringBuilder sb = new StringBuilder("📋 audit #").append(n).append(" — ").append(g.getKey()).append(" (")
                .append(errs > 0 ? errs + " error(s) + " : "").append(warns).append(" warning(s)");
        // truthful age only for real-world timestamps (synthetic/clock-less entries
        // carry tiny stamps — no absurd minute counts, same doctrine as the digest)
        if (newest >= 946684800000L) sb.append(" · last seen ").append(Math.max(0, (System.currentTimeMillis() - newest) / 60000)).append(" min ago");
        sb.append("):");
        int lines = 0, skipped = 0;
        for (LogWatch.Entry e : g.getValue()) {
            String head = "[" + e.level + (e.count > 1 ? " ×" + e.count : "") + "] " + e.message;
            String block = e.thrown.isEmpty() ? head : head + "\n" + e.thrown;
            String[] parts = block.split("\n");
            if (lines + parts.length > 25) { skipped += parts.length; continue; }
            for (String line : parts) { sb.append("\n").append(line); lines++; }
        }
        if (skipped > 0) sb.append("\n… +").append(skipped).append(" more line(s) — full data in logs/latest.log");
        return sb.toString();
    }

    /** The fix rule matching group #n, or null (out-of-range OR no matching rule). */
    public FixRules.Rule fixRule(int n) {
        return fixRuleFor(watch.snapshot(), pluginRoots, n, fixes);
    }

    /** Static seam (smoke-pinned). */
    public static FixRules.Rule fixRuleFor(List<LogWatch.Entry> entries, Map<String, String> pluginRoots,
                                           int n, FixRules rules) {
        List<Map.Entry<String, List<LogWatch.Entry>>> groups = orderedGroups(entries, pluginRoots);
        if (n < 1 || n > groups.size() || rules == null) return null;
        return rules.match(groupHay(groups.get(n - 1).getValue()));
    }

    /** "no such audit source" text (shared by show-less fix paths). */
    public String noSuchSource(int n) {
        int size = orderedGroups(watch.snapshot(), pluginRoots).size();
        return "no such audit source #" + n + (size == 0 ? " — the audit ring is empty" : " — valid: 1.." + size);
    }

    /** True when group #n exists but has no matching rule (→ the AI-guess path). */
    public String groupSource(int n) {
        List<Map.Entry<String, List<LogWatch.Entry>>> groups = orderedGroups(watch.snapshot(), pluginRoots);
        return n >= 1 && n <= groups.size() ? groups.get(n - 1).getKey() : null;
    }

    /** AI-guess context for group #n (source + bounded hay). */
    public String groupContextForAi(int n, int budget) {
        List<Map.Entry<String, List<LogWatch.Entry>>> groups = orderedGroups(watch.snapshot(), pluginRoots);
        if (n < 1 || n > groups.size()) return null;
        var g = groups.get(n - 1);
        String lvl = severity(g.getValue()) >= 2 ? "ERROR" : "WARN";
        String hay = groupHay(g.getValue());
        if (hay.length() > budget) hay = hay.substring(0, budget) + "…";
        return "source: " + g.getKey() + " (" + lvl + ")\n" + hay;
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

    /** Details for `audit updates`: one line per checked plugin + truthful check age. */
    public String updatesDetail() {
        List<UpdateRadar.CheckResult> rs = radarResults;
        if (rs == null) return "update radar: first check still pending (runs ~60 s after boot) — try again shortly.";
        return updatesDetailOf(rs, radarStamp);
    }

    /** Static seam (smoke-pinned). stamp 0 → no age claim. Never throws. */
    public static String updatesDetailOf(List<UpdateRadar.CheckResult> rs, long stamp) {
        StringBuilder sb = new StringBuilder("update radar detail:");
        for (UpdateRadar.CheckResult r : rs) {
            sb.append("\n• ").append(r.pluginName()).append(" — installed ").append(r.current())
              .append(r.status() == UpdateRadar.Status.BEHIND
                      ? " · LATEST " + r.latest() + " ⬆ (behind!)" + preReleaseTag(r.latest())
                      : r.status() == UpdateRadar.Status.CURRENT ? " · current ✓"
                      : r.status() == UpdateRadar.Status.AHEAD ? " · ahead of public release (!)"
                      : r.status() == UpdateRadar.Status.OFFLINE ? " · check failed (offline)"
                      : " · latest unknown");
        }
        if (stamp > 0) {
            sb.append("\nlast checked ").append(Math.max(0, (System.currentTimeMillis() - stamp) / 60000))
              .append(" min ago (auto re-check daily; `audit updates` also kicks a silent re-check)");
        }
        return sb.toString();
    }

    /** v0.26.0 — risk note for pre-release targets (answers "is it safe?" truthfully). */
    public static String preReleaseTag(String latest) {
        if (latest != null && java.util.regex.Pattern.compile("(?i)snapshot|beta|alpha|-rc\\b|\\brc\\d").matcher(latest).find()) {
            return " · ⚠ pre-release build — test on a copy first";
        }
        return "";
    }
}
