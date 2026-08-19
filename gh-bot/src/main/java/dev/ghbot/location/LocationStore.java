package dev.ghbot.location;

import dev.ghbot.log.WIBLogger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Named locations ("spawn", "lobby", "portal") — persistent across restarts.
 * Used for hands-off console building ("build the hub at spawn").
 */
public class LocationStore {

    public record NamedLocation(String world, double x, double y, double z, float yaw, float pitch) {
        public String coord() {
            return (int) x + ", " + (int) y + ", " + (int) z;
        }
    }

    private final Path file;
    private final Map<String, NamedLocation> locations = new TreeMap<>();
    private final WIBLogger log;

    public LocationStore(Path dataFolder, WIBLogger log) {
        this.file = dataFolder.resolve("locations.yml");
        this.log = log;
        load();
    }

    public Map<String, NamedLocation> all() { return locations; }

    public NamedLocation get(String name) { return locations.get(norm(name)); }

    public boolean has(String name) { return locations.containsKey(norm(name)); }

    public NamedLocation put(String name, Location loc) {
        NamedLocation n = new NamedLocation(
                loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        locations.put(norm(name), n);
        save();
        return n;
    }

    public NamedLocation remove(String name) {
        NamedLocation n = locations.remove(norm(name));
        if (n != null) save();
        return n;
    }

    /** Resolve a named location to a Location in the given world context, or null. */
    public Location toBukkit(NamedLocation n, java.util.List<World> worlds) {
        World w = null;
        for (World ww : worlds) if (ww.getName().equalsIgnoreCase(n.world())) { w = ww; break; }
        if (w == null && !worlds.isEmpty()) w = worlds.get(0);
        if (w == null) return null;
        return new Location(w, n.x(), n.y(), n.z(), n.yaw(), n.pitch());
    }

    /** v0.21.20 — re-read locations.yml (used by /gh reload). */
    public void reload() { locations.clear(); load(); }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            if (y.getConfigurationSection("locations") != null) {
                for (String k : y.getConfigurationSection("locations").getKeys(false)) {
                    String base = "locations." + k + ".";
                    locations.put(norm(k), new NamedLocation(
                            y.getString(base + "world", "world"),
                            y.getDouble(base + "x"), y.getDouble(base + "y"), y.getDouble(base + "z"),
                            (float) y.getDouble(base + "yaw"), (float) y.getDouble(base + "pitch")));
                }
            }
            log.info("Loaded " + locations.size() + " named location(s).");
        } catch (Exception e) {
            log.error("Could not load locations.yml", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            YamlConfiguration y = new YamlConfiguration();
            for (Map.Entry<String, NamedLocation> e : locations.entrySet()) {
                String base = "locations." + e.getKey() + ".";
                NamedLocation n = e.getValue();
                y.set(base + "world", n.world());
                y.set(base + "x", n.x());
                y.set(base + "y", n.y());
                y.set(base + "z", n.z());
                y.set(base + "yaw", (double) n.yaw());
                y.set(base + "pitch", (double) n.pitch());
            }
            Files.writeString(file, y.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Could not save locations.yml", e);
        }
    }

    private static String norm(String name) {
        return name == null ? "" : name.trim().toLowerCase().replace(" ", "_");
    }
}
