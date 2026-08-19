package dev.ghbot.avatar;

import dev.ghbot.bot.GHBot;
import dev.ghbot.log.WIBLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 12 — markers. Named waypoints (invisible armor stands with a name tag)
 * at important spots, so you can navigate your builds hands-off.
 * Names+coords live in bot memory (session-persisted); the stands persist in
 * the world themselves.
 */
public class MarkerService {

    private final WIBLogger log;
    private final Map<String, Map<String, int[]>> markers = new LinkedHashMap<>(); // botId -> name -> {x,y,z}

    public MarkerService(WIBLogger log) {
        this.log = log;
    }

    public void place(GHBot bot, Location loc, String name) {
        String key = bot.id() + "|" + name;
        Map<String, int[]> m = markers.computeIfAbsent(bot.id(), k -> new LinkedHashMap<>());
        m.put(name, new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()});
        if (Bukkit.getServer() != null) {
            try {
                ArmorStand stand = loc.getWorld().spawn(loc.clone().add(0.5, -1.2, 0.5), ArmorStand.class);
                stand.setInvisible(true);
                stand.setInvulnerable(true);
                stand.setSilent(true);
                stand.setPersistent(true);
                stand.setGravity(false);
                stand.setMarker(true);
                stand.setCustomName("§b§l◆ " + name);
                stand.setCustomNameVisible(true);
            } catch (Throwable t) {
                log.error("Could not place marker " + name, t);
            }
        }
        log.info("[" + bot.id() + "] marker placed: " + name + " at "
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
    }

    public boolean remove(GHBot bot, String name) {
        Map<String, int[]> m = markers.get(bot.id());
        return m != null && m.remove(name) != null;
    }

    public Map<String, int[]> all(GHBot bot) {
        return markers.getOrDefault(bot.id(), new LinkedHashMap<>());
    }

    /** Restore marker names into memory after session load. */
    public void restoreFromMemory(GHBot bot) {
        Object o = bot.memory().get("markers");
        if (o instanceof java.util.Map<?, ?> mm) {
            Map<String, int[]> m = markers.computeIfAbsent(bot.id(), k -> new LinkedHashMap<>());
            mm.forEach((k, v) -> {
                if (v instanceof java.util.List<?> list && list.size() >= 3) {
                    m.put(String.valueOf(k), new int[]{
                            ((Number) list.get(0)).intValue(),
                            ((Number) list.get(1)).intValue(),
                            ((Number) list.get(2)).intValue()});
                }
            });
        }
    }

    public void saveToMemory(GHBot bot) {
        Map<String, java.util.List<Integer>> out = new LinkedHashMap<>();
        all(bot).forEach((k, v) -> out.put(k, java.util.List.of(v[0], v[1], v[2])));
        bot.memory().put("markers", out);
    }
}
