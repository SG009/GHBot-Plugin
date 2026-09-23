package dev.ghbot.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v0.24.0 — Phase B: quiet update radar. Once per boot (delayed) and once a day,
 * one async pass checks ONLY the plugins actually installed (+ Paper itself) against:
 * - Modrinth  /v2/project/{slug}/version   (LuckPerms, Geyser, floodgate, Via* — research-verified)
 * - GitHub    /repos/EssentialsX/Essentials/releases/latest (EssentialsX)
 * - fill v3   /projects/paper/versions/{mc}/builds/latest   (Paper build number)
 * Reports ONLY when behind, and each (plugin → latest) notice only once until it
 * changes (persisted to plugins/GHBot/updates-notified.yml). Offline → silent.
 * All parsing/compare/notify logic is headlessly pinned; only fetch() touches net.
 */
public final class UpdateRadar {

    public enum Status { CURRENT, BEHIND, AHEAD, UNKNOWN, OFFLINE }

    public record Source(String pluginName, String kind, String target) {
        public static final String MODRINTH = "modrinth";
        public static final String GITHUB_LATEST = "github-latest";
    }

    public record CheckResult(String pluginName, String current, String latest, Status status) {}

    /** Owner-stack sources (checked only when the plugin is actually installed). */
    public static final List<Source> SOURCES = List.of(
            new Source("Essentials", Source.GITHUB_LATEST, "EssentialsX/Essentials"),
            new Source("Geyser-Spigot", Source.MODRINTH, "geyser"),
            new Source("floodgate", Source.MODRINTH, "floodgate"),
            new Source("ViaVersion", Source.MODRINTH, "viaversion"),
            new Source("ViaBackwards", Source.MODRINTH, "viabackwards"),
            new Source("ViaRewind", Source.MODRINTH, "viarewind"),
            new Source("LuckPerms", Source.MODRINTH, "luckperms"));

    private static final String UA = "GHBot-Plugin/0.24 (https://github.com/SG009/GHBot-Plugin)";
    private static final long MAX_BYTES = 1_500_000;

    /* ── one full pass ────────────────────────────────────────────────── */

    /** installedVersions: pluginName → description version; paperBuild: current Paper build (0 = unknown). */
    public static List<CheckResult> runOnce(Map<String, String> installedVersions,
                                            int paperBuild, String mcVersion) {
        List<CheckResult> out = new ArrayList<>();
        for (Source s : SOURCES) {
            String cur = installedVersions.get(s.pluginName());
            if (cur == null) continue;   // not installed → skip silently
            try {
                String latest = switch (s.kind()) {
                    case Source.MODRINTH -> modrinthLatest(fetch(
                            "https://api.modrinth.com/v2/project/" + s.target() + "/version"),
                            Set.of("paper", "spigot", "bukkit"));
                    case Source.GITHUB_LATEST -> githubTag(fetch(
                            "https://api.github.com/repos/" + s.target() + "/releases/latest"));
                    default -> null;
                };
                if (latest == null || latest.isBlank()) {
                    out.add(new CheckResult(s.pluginName(), cur, "", Status.UNKNOWN));
                } else {
                    out.add(new CheckResult(s.pluginName(), cur, latest, compare(cur, latest)));
                }
            } catch (Throwable t) {
                out.add(new CheckResult(s.pluginName(), cur, "", Status.OFFLINE));
            }
        }
        // Paper itself
        try {
            String json = fetch("https://fill.papermc.io/v3/projects/paper/versions/" + mcVersion + "/builds/latest");
            int latestBuild = paperBuildFromFill(json);
            if (latestBuild > 0 && paperBuild > 0) {
                Status st = paperBuild < latestBuild ? Status.BEHIND
                        : paperBuild == latestBuild ? Status.CURRENT : Status.AHEAD;
                out.add(new CheckResult("Paper", "build " + paperBuild, "build " + latestBuild, st));
            } else {
                out.add(new CheckResult("Paper", "build " + paperBuild, "", Status.UNKNOWN));
            }
        } catch (Throwable t) {
            out.add(new CheckResult("Paper", "build " + paperBuild, "", Status.OFFLINE));
        }
        return out;
    }

    /** Which results deserve a FRESH "behind" notice: behind AND latest != what
     *  we already notified for that plugin (so repeats stay quiet, but a NEWER
     *  later version re-notifies). prevNotified is mutated by the caller after use. */
    public static List<CheckResult> freshNotices(List<CheckResult> results, Map<String, String> prevNotified) {
        List<CheckResult> out = new ArrayList<>();
        for (CheckResult r : results) {
            if (r.status() != Status.BEHIND) continue;
            String prev = prevNotified.get(r.pluginName());
            if (prev == null || !prev.equals(r.latest())) out.add(r);
        }
        return out;
    }

    /** Compact one-line status for digests: upgrades first, never throws. */
    public static String line(List<CheckResult> results) {
        if (results == null) return "updates: checking…";
        if (results.isEmpty()) return "updates: no data";
        StringBuilder up = new StringBuilder();
        int behind = 0, current = 0;
        for (CheckResult r : results) {
            if (r.status() == Status.BEHIND) {
                behind++;
                if (behind <= 4) {
                    if (up.length() > 0) up.append(" · ");
                    up.append(r.pluginName()).append(' ').append(r.current())
                      .append(" → ").append(r.latest()).append(" ⬆");
                }
            } else if (r.status() == Status.CURRENT) current++;
        }
        if (behind == 0) return "updates: all current (" + current + " checked)";
        if (behind > 4) up.append(" · +").append(behind - 4).append(" more");
        return "updates: " + up + " — ask 'audit updates' for details";
    }

    /* ── compare + parse (all headless-pinned) ────────────────────────── */

    /** Lenient version compare ("2.11.1" vs "2.11.3-b1245", "2.22.0" vs "2.22.0"). */
    public static Status compare(String current, String latest) {
        int[] a = nums(current), b = nums(latest);
        if (a == null || b == null) return Status.UNKNOWN;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x < y) return Status.BEHIND;
            if (x > y) return Status.AHEAD;
        }
        return Status.CURRENT;
    }

    private static int[] nums(String v) {
        if (v == null) return null;
        var m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)*)").matcher(v);
        if (!m.find()) return null;
        String[] parts = m.group(1).split("\\.");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** First Modrinth entry whose loaders include a preferred one; fallback: first entry. */
    @SuppressWarnings("unchecked")
    public static String modrinthLatest(String json, Set<String> preferredLoaders) {
        Object root = new org.yaml.snakeyaml.Yaml().load(json);
        if (!(root instanceof List<?> list) || list.isEmpty()) return null;
        String first = null;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> e = (Map<String, Object>) o;
            Object vn = e.get("version_number");
            if (vn == null) continue;
            if (first == null) first = String.valueOf(vn);
            Object loaders = e.get("loaders");
            if (loaders instanceof List<?> ls) {
                for (Object l : ls) {
                    if (l != null && preferredLoaders.contains(String.valueOf(l).toLowerCase())) {
                        return String.valueOf(vn);
                    }
                }
            }
        }
        return first;
    }

    @SuppressWarnings("unchecked")
    public static String githubTag(String json) {
        Object root = new org.yaml.snakeyaml.Yaml().load(json);
        if (!(root instanceof Map)) return null;
        Object tag = ((Map<String, Object>) root).get("tag_name");
        return tag == null ? null : String.valueOf(tag);
    }

    @SuppressWarnings("unchecked")
    public static int paperBuildFromFill(String json) {
        Object root = new org.yaml.snakeyaml.Yaml().load(json);
        if (!(root instanceof Map)) return -1;
        Object id = ((Map<String, Object>) root).get("id");
        if (id instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(id)); } catch (Throwable t) { return -1; }
    }

    /** Small bounded GET (Modrinth needs a UA; 1.5 MB cap; 8 s timeouts). */
    public static String fetch(String url) throws Exception {
        var conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        try (var in = conn.getInputStream()) {
            var buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (buf.size() + n > MAX_BYTES) throw new IllegalStateException("response too large");
                buf.write(chunk, 0, n);
            }
            return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    /* ── persistence of "already notified" versions ───────────────────── */

    public static Map<String, String> loadNotified(java.nio.file.Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            if (!java.nio.file.Files.exists(file)) return out;
            for (String ln : java.nio.file.Files.readAllLines(file)) {
                int eq = ln.indexOf('=');
                if (eq > 0) out.put(ln.substring(0, eq).trim(), ln.substring(eq + 1).trim());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void saveNotified(java.nio.file.Path file, Map<String, String> notified) {
        try {
            StringBuilder sb = new StringBuilder("# GHBot update radar — already-notified latest versions\n");
            notified.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, sb.toString());
        } catch (Throwable ignored) {}
    }

    private UpdateRadar() {}
}
