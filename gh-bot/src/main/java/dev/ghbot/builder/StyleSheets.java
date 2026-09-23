package dev.ghbot.builder;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.25.0 — Phase C "Good-Result Grade" pack: STYLE SHEETS.
 *
 * Hand-curated, zero-AI quality boosters: a keyword in the prompt ("abandoned",
 * "medieval"…) maps to palette hints + hard rules the generator must obey
 * (block-mix ratios, missing-block percentages, detail passes). Owner-editable
 * at plugins/GHBot/styles.yml — no recompile, no fine-tune, works on the
 * weakest free-tier cloud model because the taste lives in the sheet, not
 * the weights.
 */
public final class StyleSheets {

    /** One style: trigger keywords, palette hints, rules. */
    public static final class Style {
        public final String id;
        public final List<String> match = new ArrayList<>();
        public final List<String> palette = new ArrayList<>();
        public final List<String> rules = new ArrayList<>();
        Style(String id) { this.id = id; }
    }

    private final Map<String, Style> styles = new LinkedHashMap<>();

    public int size() { return styles.size(); }
    public java.util.Collection<Style> all() { return styles.values(); }

    /** Load from owner file; falls back to an empty set on any trouble (never throws). */
    public static StyleSheets load(File f) {
        StyleSheets ss = new StyleSheets();
        if (f == null || !f.isFile()) return ss;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            var sec = y.getConfigurationSection("styles");
            if (sec == null) return ss;
            for (String id : sec.getKeys(false)) {
                Style s = new Style(id.toLowerCase());
                s.match.addAll(sec.getStringList(id + ".match"));
                s.palette.addAll(sec.getStringList(id + ".palette"));
                s.rules.addAll(sec.getStringList(id + ".rules"));
                if (!s.match.isEmpty()) ss.styles.put(s.id, s);
            }
        } catch (Throwable ignored) {}
        return ss;
    }

    /** Headless seam for SmokeTest: parse from raw YAML text. */
    public static StyleSheets fromString(String yaml) {
        StyleSheets ss = new StyleSheets();
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(yaml);
            var sec = y.getConfigurationSection("styles");
            if (sec == null) return ss;
            for (String id : sec.getKeys(false)) {
                Style s = new Style(id.toLowerCase());
                s.match.addAll(sec.getStringList(id + ".match"));
                s.palette.addAll(sec.getStringList(id + ".palette"));
                s.rules.addAll(sec.getStringList(id + ".rules"));
                if (!s.match.isEmpty()) ss.styles.put(s.id, s);
            }
        } catch (Throwable ignored) {}
        return ss;
    }

    /** First style whose any keyword appears in the prompt (word-boundary, ≥4 chars). */
    public Style match(String prompt) {
        if (prompt == null) return null;
        String p = " " + prompt.toLowerCase() + " ";
        for (Style s : styles.values()) {
            for (String kw : s.match) {
                String k = kw.trim().toLowerCase();
                if (k.length() >= 4 && p.contains(" " + k)) return s;
            }
        }
        return null;
    }

    /** The prompt injection block for a matched style ("" when nothing matches). */
    public String inject(String prompt) {
        Style s = match(prompt);
        if (s == null) return "";
        StringBuilder sb = new StringBuilder("Style sheet \"").append(s.id).append("\" — obey it exactly:");
        if (!s.palette.isEmpty()) sb.append("\npalette hints: ").append(String.join(", ", s.palette));
        for (String r : s.rules) sb.append("\n- ").append(r);
        return sb.toString();
    }

    private StyleSheets() {}
}
