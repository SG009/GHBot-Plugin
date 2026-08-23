package dev.ghbot.schematic;

import dev.ghbot.bot.GHBot;
import dev.ghbot.builder.VoxelModel;
import dev.ghbot.log.WIBLogger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 8 — schematic export/paste/library.
 * Exports a voxel model (or a live world region) to every major format:
 * Sponge v2/v3, Classic, Litematica, Vanilla .nbt. Files go to
 * plugins/GHBot/schematics/ (and the library is the teaching base, Phase 12).
 */
public class SchematicService {

    private final Path schematicsDir;
    private final WIBLogger log;
    private final List<SchematicCodec> codecs;

    public SchematicService(Path dataFolder, WIBLogger log) {
        this.schematicsDir = dataFolder.resolve("schematics");
        this.log = log;
        this.codecs = List.of(
                new SpongeV2Codec(),
                new SpongeV3Codec(),
                new ClassicCodec(),
                new LitematicaCodec(),
                new VanillaNbtCodec());
    }

    public Path dir() { return schematicsDir; }
    public List<SchematicCodec> codecs() { return codecs; }

    /**
     * v0.22.2 — resolve a user-supplied library file name SAFELY (AUDIT P1-3):
     * the path is normalized and must stay inside the schematics directory —
     * `../../server.properties` style traversal returns null. No extension
     * allowlist (the importer sniffs content); an empty/blank name returns null.
     */
    public Path resolveInLibrary(String name) {
        if (name == null || name.isBlank()) return null;
        Path base = schematicsDir.toAbsolutePath().normalize();
        Path f;
        try {
            f = base.resolve(name).normalize();
        } catch (Exception e) {
            return null;
        }
        if (!f.startsWith(base)) return null;
        return f;
    }

    /** Export a voxel model to files. format "all" or a codec id. Returns written paths. */
    public List<Path> export(String name, VoxelModel model, String format) throws IOException {
        Files.createDirectories(schematicsDir);
        List<Path> written = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Map.Entry<Long, String> e : model.entries()) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        if (minX == Integer.MAX_VALUE) return written;
        int w = maxX - minX + 1, h = maxY - minY + 1, d = maxZ - minZ + 1;

        for (SchematicCodec codec : codecs) {
            if (!format.equalsIgnoreCase("all") && !codec.formatName().equalsIgnoreCase(format)) continue;
            try {
                byte[] bytes = codec.export(model.entriesMapSafe(), minX, minY, minZ, w, h, d);
                String safeName = name.replaceAll("[^A-Za-z0-9_-]", "_");
                Path f = schematicsDir.resolve(safeName + codec.fileExtension());
                Files.write(f, bytes);
                written.add(f);
            } catch (Exception e) {
                log.error("Export " + codec.formatName() + " failed for " + name, e);
            }
        }
        return written;
    }

    /** Capture a live world region into a voxel model (for export of built things). */
    public VoxelModel capture(Location corner1, Location corner2) {
        World w = corner1.getWorld();
        VoxelModel m = new VoxelModel();
        if (w == null) return m;
        int x0 = Math.min(corner1.getBlockX(), corner2.getBlockX()), x1 = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int y0 = Math.min(corner1.getBlockY(), corner2.getBlockY()), y1 = Math.max(corner1.getBlockY(), corner2.getBlockY());
        int z0 = Math.min(corner1.getBlockZ(), corner2.getBlockZ()), z1 = Math.max(corner1.getBlockZ(), corner2.getBlockZ());
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    Material mat = w.getBlockAt(x, y, z).getType();
                    if (mat != Material.AIR && mat != Material.CAVE_AIR && mat != Material.VOID_AIR) {
                        m.set(x - x0, y - y0, z - z0, mat.name().toLowerCase());
                    }
                }
        return m;
    }

    /** List library files. */
    public List<Path> library() throws IOException {
        if (!Files.exists(schematicsDir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(schematicsDir)) {
            stream.filter(p -> p.toString().endsWith(".schem") || p.toString().endsWith(".schematic")
                            || p.toString().endsWith(".litematic") || p.toString().endsWith(".nbt"))
                    .sorted().forEach(out::add);
        }
        return out;
    }
}
