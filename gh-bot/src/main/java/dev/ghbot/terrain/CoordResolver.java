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
