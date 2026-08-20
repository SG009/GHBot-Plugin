package dev.ghbot.terrain;

import org.bukkit.Location;
import org.bukkit.World;
import dev.ghbot.location.LocationStore;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves "where" arguments to a Location:
 *   here / at me / at <name> · X Y Z · in front of me · next to the portal (etc.)
 * Falls back to a named location from the sender's memory if unknown.
 */
public final class CoordResolver {

    private static final Pattern XYZ = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)[ ,]+(-?\\d+(?:\\.\\d+)?)[ ,]+(-?\\d+(?:\\.\\d+)?)");

    /**
     * Resolve a target token to a Location. Supports:
     *   here / at me / me / my position · in front of me · X Y Z ·
     *   <playerName> (Bedrock dot-prefix ok, e.g. ".SerthGembel009", case-insensitive, fuzzy) ·
     *   named locations (Phase 4).
     */
    public static Location resolve(CommandSender sender, String where, Location fallback) {
        if (where == null || where.isBlank()) return fallback;
        String w = where.trim().toLowerCase();

        // explicit X Y Z — v0.21.32: works for players AND console/tools (uses the fallback/first world)
        Matcher m = XYZ.matcher(w);
        if (m.find()) {
            org.bukkit.World world = null;
            if (sender instanceof Player p) world = p.getWorld();
            else if (fallback != null) world = fallback.getWorld();
            else if (org.bukkit.Bukkit.getServer() != null && !org.bukkit.Bukkit.getWorlds().isEmpty())
                world = org.bukkit.Bukkit.getWorlds().get(0);
            if (world != null) {
                return new Location(world,
                        Double.parseDouble(m.group(1)),
                        Double.parseDouble(m.group(2)),
                        Double.parseDouble(m.group(3)));
            }
        }

        // player name? (also from console — so you can scan around a friend)
        Location byPlayer = resolvePlayer(w);
        if (byPlayer != null) return byPlayer;

        if (sender instanceof Player p) {
            switch (w) {
                case "here", "at me", "me", "my position" -> { return p.getLocation(); }
                case "in front of me", "front", "ahead" -> {
                    Location l = p.getLocation();
                    return l.add(l.getDirection().multiply(5));
                }
                default -> {
                    // named location stored in sender's memory (saved via save-location in Phase 4)
                    Location named = namedFromMemory(p, w);
                    if (named != null) return named;
                }
            }
        } else if (w.equals("here")) {
            return fallback; // console has no position
        }
        return null;
    }

    /**
     * v0.22.1 — one shared "where" parser for scan/find/look/set/replace/terraform.
     * Accepts: "at 86 86 262", "86 86 262", "86,86,262", "here"/"me", or a player
     * name — by joining raw args (dropping a leading "at"/"to" keyword) and handing
     * the phrase to {@link #resolve}. Returns null when nothing matches.
     */
    public static Location parseWhere(CommandSender sender, String[] args, Location fallback) {
        if (args == null || args.length == 0) return fallback;
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null || a.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(a);
        }
        String w = sb.toString().trim();
        if (w.isEmpty()) return fallback;
        String low = w.toLowerCase();
        if (low.startsWith("at ")) w = w.substring(3).trim();
        else if (low.startsWith("to ")) w = w.substring(3).trim();
        return resolve(sender, w, fallback);
    }

    /** v0.22.1 — normalized scan-target: (radius, where-phrase) from raw scan args. */
    public record ScanTarget(int radius, String where) {}

    /**
     * v0.22.1 — "x z" (2 ints) or "x y z" (3 ints) → an offset Location from base;
     * null otherwise. Used by `paste <file> 30 10` (the batch test showed the AI
     * passing 2-number x/z offsets that the old paste code silently ignored).
     * Works headless (Location is a plain data holder).
     */
    public static Location offset(String[] args, Location base) {
        if (base == null || args == null) return null;
        java.util.List<Integer> ints = new java.util.ArrayList<>();
        for (String a : args) {
            if (a != null && a.trim().matches("-?\\d+")) ints.add(Integer.parseInt(a.trim()));
        }
        if (ints.size() == 2) {
            Location l = base.clone();
            l.setX(ints.get(0)); l.setZ(ints.get(1));
            return l;
        }
        if (ints.size() == 3) {
            Location l = base.clone();
            l.setX(ints.get(0)); l.setY(ints.get(1)); l.setZ(ints.get(2));
            return l;
        }
        return null;
    }

    /**
     * v0.22.1 — single source of truth for parsing `scan …` args, shared by the
     * in-game command handler AND AutoTools (so they can never drift apart):
     *   0 ints  → default radius, where = any words (here/me/player)
     *   1 int   → radius
     *   3 ints  → coordinates (default radius)
     *   4 ints  → radius + 3 coordinates
     *   "at" keyword is dropped; coordinates win over a where-phrase.
     * Radius is clamped to [1, 200] to match the old handler.
     */
    public static ScanTarget scanTarget(String[] args, int defaultRadius) {
        if (args == null || args.length == 0) return new ScanTarget(defaultRadius, null);
        java.util.List<Integer> ints = new java.util.ArrayList<>();
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String a : args) {
            String t = a == null ? "" : a.trim();
            if (t.isEmpty() || t.equalsIgnoreCase("at")) continue;
            if (t.matches("-?\\d+")) ints.add(Integer.parseInt(t));
            else words.add(t);
        }
        int radius = Math.max(1, Math.min(200, defaultRadius));
        String where = null;
        int n = ints.size();
        if (n >= 3) {
            int x = ints.get(n - 3), y = ints.get(n - 2), z = ints.get(n - 1);
            where = x + " " + y + " " + z;
            if (n == 4) radius = Math.max(1, Math.min(200, ints.get(0)));
        } else if (n == 1) {
            radius = Math.max(1, Math.min(200, ints.get(0)));
        } else if (n == 2) {
            radius = Math.max(1, Math.min(200, ints.get(0)));
        }
        if (!words.isEmpty() && where == null) where = String.join(" ", words);
        return new ScanTarget(radius, where);
    }

    /**
     * Resolve a player name (optionally with the Bedrock "." prefix, e.g. ".SerthGembel009")
     * to their location. Case-insensitive; falls back to a "starts with" fuzzy match.
     * Works for console too — like your old scan.js "scan [me|playerName] block radius".
     */
    public static Location resolvePlayer(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        if (n.startsWith("@")) n = n.substring(1);
        // strip Bedrock dot-prefix (".SerthGembel009" -> "SerthGembel009")
        if (n.startsWith(".")) n = n.substring(1);
        if (n.equalsIgnoreCase("me")) return null; // 'me' is sender-relative, handled elsewhere
        if (Bukkit.getServer() == null) return null;
        String lower = n.toLowerCase();
        // exact (case-insensitive)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(n)) return p.getLocation();
        }
        // fuzzy: starts-with
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(lower)) return p.getLocation();
        }
        return null;
    }

    /** Static hook set by the plugin at enable — resolves named locations. */
    private static LocationStore LOCATIONS;

    public static void setLocationStore(LocationStore store) { LOCATIONS = store; }

    private static Location namedFromMemory(Player p, String name) {
        if (LOCATIONS == null || Bukkit.getServer() == null) return null;
        var n = LOCATIONS.get(name);
        if (n == null) return null;
        return LOCATIONS.toBukkit(n, Bukkit.getWorlds());
    }

    private CoordResolver() {}
}
