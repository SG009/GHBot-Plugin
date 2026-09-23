package dev.ghbot.schematic;

import dev.ghbot.log.WIBLogger;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The build-learning dataset (NotebookLM-style): persistent store of
 * {@link LearningSample}s — one per imported/downloaded schematic.
 * Phase 12's retrieval-augmented design reads from here.
 */
public class LearningDataset {

    private final Path file;
    private final List<LearningSample> samples = new ArrayList<>();
    private final WIBLogger log;

    public LearningDataset(Path dataFolder, WIBLogger log) {
        this.file = dataFolder.resolve("learning_dataset.yml");
        this.log = log;
        load();
    }

    public List<LearningSample> all() { return samples; }

    public void add(LearningSample s) {
        samples.add(s);
        save();
    }

    public boolean remove(String name) {
        boolean removed = samples.removeIf(s -> s.name.equalsIgnoreCase(name));
        if (removed) save();
        return removed;
    }

    public void clear() {
        samples.clear();
        save();
    }

    public int size() { return samples.size(); }

    /** Retrieve up to n samples relevant to a prompt (keyword match on name/style/palette). */
    public java.util.List<LearningSample> retrieve(String prompt, int n) {
        String p = (prompt == null ? "" : prompt.toLowerCase());
        java.util.List<LearningSample> hits = new ArrayList<>();
        for (LearningSample s : samples) {
            StringBuilder hay = new StringBuilder(s.name.toLowerCase());
            s.styleTags.forEach(t -> hay.append(' ').append(t.toLowerCase()));
            s.palette.keySet().forEach(k -> hay.append(' ').append(k.toLowerCase()));
            boolean match = false;
            for (String w : p.split("\\s+")) {
                if (w.length() >= 4 && hay.indexOf(w) >= 0) { match = true; break; }
            }
            if (match) hits.add(s);
        }
        return hits.subList(0, Math.min(n, hits.size()));
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            YamlConfiguration y = new YamlConfiguration();
            int i = 0;
            for (LearningSample s : samples) {
                String base = "samples." + (i++) + ".";
                y.set(base + "name", s.name);
                y.set(base + "file", s.file);
                y.set(base + "format", s.format);
                y.set(base + "width", s.width);
                y.set(base + "height", s.height);
                y.set(base + "length", s.length);
                y.set(base + "blocks", s.blocks);
                y.set(base + "structure", s.structure);
                y.set(base + "source-url", s.sourceUrl);
                if (s.goldSpec != null && !s.goldSpec.isEmpty()) y.set(base + "gold-spec", s.goldSpec);
                Map<String, Object> pal = new LinkedHashMap<>();
                s.palette.forEach((k, v) -> pal.put(k, v));
                y.set(base + "palette", pal);
                y.set(base + "style-tags", new ArrayList<>(s.styleTags));
            }
            Files.writeString(file, y.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Could not save learning dataset", e);
        }
    }

    @SuppressWarnings("unchecked")
    /** v0.21.20 — re-read learning_dataset.yml (used by /gh reload). */
    public void reload() { samples.clear(); load(); }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            var sec = y.getConfigurationSection("samples");
            if (sec == null) return;
            for (String key : sec.getKeys(false)) {
                String base = "samples." + key + ".";
                LearningSample s = new LearningSample();
                s.name = y.getString(base + "name", "unnamed");
                s.file = y.getString(base + "file", "");
                s.format = y.getString(base + "format", "");
                s.width = y.getInt(base + "width");
                s.height = y.getInt(base + "height");
                s.length = y.getInt(base + "length");
                s.blocks = y.getInt(base + "blocks");
                s.structure = y.getString(base + "structure", "unknown");
                s.sourceUrl = y.getString(base + "source-url", "");
                s.goldSpec = y.getString(base + "gold-spec", "");
                Object pal = y.get(base + "palette");
                if (pal instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> s.palette.put(String.valueOf(k), v instanceof Number n ? n.intValue() : 0));
                } else if (pal instanceof org.bukkit.configuration.ConfigurationSection cs) {
                    for (String k : cs.getKeys(false)) s.palette.put(k, cs.getInt(k));
                }
                s.styleTags.addAll(y.getStringList(base + "style-tags"));
                if (s.styleTags.isEmpty()) s.styleTags.add("generic");
                samples.add(s);
            }
            log.info("Loaded " + samples.size() + " learning sample(s).");
        } catch (Exception e) {
            log.error("Could not load learning dataset", e);
        }
    }
}
