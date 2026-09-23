package dev.ghbot.schematic;

import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.log.WIBLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 8b — build-learning dataset commands:
 *   schem download <name> <url>   — fetch a schematic into the library
 *   schem import <file> [name]    — read a schematic into a learning sample
 *   dataset list / remove <name> / clear
 */
public final class DatasetCommands {

    private DatasetCommands() {}

    public static void register(GHBot bot, CommandBridge bridge, SchematicService schematics,
                                LearningDataset dataset, SchematicDownloader downloader,
                                dev.ghbot.review.GhostService ghosts, WIBLogger log) {
        CommandRegistry r = bridge.registryOf(bot);

        // ── schem download ──
        r.register("schem", (b, ctx) -> {
            if (ctx.args().length < 1) {
                ctx.sender().sendMessage("§eUsage: " + b.id()
                        + " schem <name> <prompt> [format|all] | schem download <name> <url> | schem import <file> [name]");
                return;
            }
            String sub = ctx.args()[0].toLowerCase();
            if (sub.equals("download")) {
                if (ctx.args().length < 3) {
                    ctx.sender().sendMessage("§eUsage: " + b.id() + " schem download <name> <url>");
                    return;
                }
                String name = ctx.args()[1];
                String url = ctx.args()[2];
                ctx.sender().sendMessage("§7[" + b.id() + "] Downloading schematic from " + url + "…");
                try {
                    Path f = downloader.download(name, url, schematics.dir());
                    // import into dataset
                    byte[] bytes = Files.readAllBytes(f);
                    var model = SchematicImporter.importFile(bytes);
                    if (model != null) {
                        LearningSample s = LearningSample.from(model, name, f.getFileName().toString(), guessFormat(f));
                        s.sourceUrl = url;
                        dataset.add(s);
                        ctx.sender().sendMessage("§a[" + b.id() + "] Downloaded + imported §f" + name
                                + "§a (" + s.blocks + " blocks) → dataset (" + dataset.size() + " samples).");
                        ctx.sender().sendMessage("§7  " + s.oneLine());
                    } else {
                        ctx.sender().sendMessage("§7[" + b.id() + "] Downloaded " + f.getFileName()
                                + " but couldn't parse it (unsupported format?). It's saved in the library.");
                    }
                } catch (Exception e) {
                    ctx.sender().sendMessage("§c[" + b.id() + "] Download failed: " + e.getMessage());
                }
                return;
            }
            if (sub.equals("import")) {
                if (ctx.args().length < 2) {
                    ctx.sender().sendMessage("§eUsage: " + b.id() + " schem import <file> [name]");
                    return;
                }
                String file = ctx.args()[1];
                String name = ctx.args().length >= 3 ? ctx.args()[2] : file.replaceFirst("\\.[^.]+$", "");
                Path f = schematics.resolveInLibrary(file);
                if (f == null) {
                    ctx.sender().sendMessage("§c[" + b.id() + "] Invalid file name — it must live inside the schematics library.");
                    return;
                }
                if (!Files.exists(f)) {
                    ctx.sender().sendMessage("§c[" + b.id() + "] No such file: " + file);
                    return;
                }
                try {
                    var model = SchematicImporter.importFile(Files.readAllBytes(f));
                    if (model == null) {
                        ctx.sender().sendMessage("§c[" + b.id() + "] Couldn't parse " + file + " (unsupported format).");
                        return;
                    }
                    LearningSample s = LearningSample.from(model, name, file, guessFormat(f));
                    dataset.add(s);
                    ctx.sender().sendMessage("§a[" + b.id() + "] Imported §f" + name
                            + "§a (" + s.blocks + " blocks) → dataset (" + dataset.size() + " samples).");
                    ctx.sender().sendMessage("§7  " + s.oneLine());
                } catch (Exception e) {
                    ctx.sender().sendMessage("§c[" + b.id() + "] Import failed: " + e.getMessage());
                }
                return;
            }
            // fallthrough to Phase 8 schem (design+export) — keep it registered too
            ctx.sender().sendMessage("§7[" + b.id() + "] schem: use '" + b.id()
                    + " schem <name> <prompt> [format|all]' to design+export, or 'schem download|import' for the dataset.");
        }, CommandRegistry.Meta.of("Design+export, or download/import schematics (dataset)", "schem <name> <prompt> [format|all] | schem download <name> <url> | schem import <file> [name]"));

        // ── teach : add the staged (or a library) build to the learning dataset ──
        r.register("teach", (b, ctx) -> {
            if (ctx.args().length < 1) {
                ctx.sender().sendMessage("§eUsage: " + b.id() + " teach <name> [staged] — name may include the file extension");
                return;
            }
            String name = ctx.args()[0];
            boolean fromStaged = ctx.args().length >= 2 && ctx.args()[1].equalsIgnoreCase("staged");
            // v0.25.0 — `teach <name> [staged] gold` marks a gold exemplar (Phase C)
            boolean gold = java.util.Arrays.stream(ctx.args()).anyMatch(a -> a.equalsIgnoreCase("gold"));
            try {
                if (fromStaged && ghosts != null && ghosts.staged(b) != null) {
                    var st = ghosts.staged(b);
                    LearningSample s = LearningSample.from(st.model, name, name + ".schem", "sponge");
                    s.sourceUrl = "staged:" + st.specName;
                    String goldNote = "";
                    if (gold) {
                        goldNote = goldNote(s, st.model);
                    }
                    dataset.add(s);
                    ctx.sender().sendMessage("§a[" + b.id() + "] Taught §f" + name + "§a from the staged build ("
                            + s.blocks + " blocks) → dataset (" + dataset.size() + ")." + goldNote);
                } else {
                    // v0.21.8: accept a bare name OR a full filename with extension.
                    String base = name.replaceAll("(?i)\\.(schem|schematic|litematic|nbt)$", "");
                    java.nio.file.Path f = findLibraryFile(schematics, base, name);
                    if (f == null) {
                        ctx.sender().sendMessage("§c[" + b.id() + "] No library build named \"" + name
                                + "\". Use " + b.id() + " schem <name> <prompt>, " + b.id()
                                + " schem download <name> <url>, or " + b.id() + " teach <name> staged. "
                                + "Tip: " + b.id() + " library lists your files (you can use the full filename).");
                        return;
                    }
                    var model = SchematicImporter.importFile(java.nio.file.Files.readAllBytes(f));
                    if (model == null) { ctx.sender().sendMessage("§cCouldn't parse " + f.getFileName()); return; }
                    LearningSample s = LearningSample.from(model, base, f.getFileName().toString(),
                            f.getFileName().toString().endsWith(".litematic") ? "litematic" : "sponge");
                    String goldNote = gold ? goldNote(s, model) : "";
                    dataset.add(s);
                    ctx.sender().sendMessage("§a[" + b.id() + "] Taught §f" + base + "§a from " + f.getFileName()
                            + " → dataset (" + dataset.size() + ")." + goldNote);
                }
            } catch (Exception e) {
                ctx.sender().sendMessage("§cTeach failed: " + e.getMessage());
            }
        }, CommandRegistry.Meta.of("Add a library file or staged build to the learning dataset (`gold` = few-shot exemplar, ≤80 blocks)", "teach <name> [staged] [gold]"));

        // ── dataset ──
        r.register("dataset", (b, ctx) -> {
            if (ctx.args().length == 0 || ctx.args()[0].equalsIgnoreCase("list")) {
                if (dataset.size() == 0) {
                    ctx.sender().sendMessage("§7[" + b.id() + "] Dataset empty. "
                            + "Use " + b.id() + " schem download <name> <url> to add schematics.");
                    return;
                }
                StringBuilder sb = new StringBuilder("§e[" + b.id() + "] Learning dataset (" + dataset.size() + "):");
                for (LearningSample s : dataset.all()) {
                    sb.append("\n§f- §a").append(s.name)
                      .append(s.goldSpec != null && !s.goldSpec.isEmpty() ? " §6[gold]" : "")
                      .append("§7  ").append(s.oneLine());
                }
                ctx.sender().sendMessage(sb.toString());
                return;
            }
            if (ctx.args()[0].equalsIgnoreCase("remove") && ctx.args().length >= 2) {
                boolean ok = dataset.remove(ctx.args()[1]);
                ctx.sender().sendMessage(ok
                        ? "§a[" + b.id() + "] Removed \"" + ctx.args()[1] + "\" from dataset."
                        : "§7[" + b.id() + "] No sample named \"" + ctx.args()[1] + "\".");
                return;
            }
            if (ctx.args()[0].equalsIgnoreCase("clear")) {
                dataset.clear();
                ctx.sender().sendMessage("§a[" + b.id() + "] Dataset cleared.");
                return;
            }
            ctx.sender().sendMessage("§eUsage: " + b.id() + " dataset list|remove <name>|clear");
        }, CommandRegistry.Meta.of("Browse/manage the build-learning dataset", "dataset list|remove <name>|clear"));
    }

    private static String guessFormat(Path f) {
        String n = f.getFileName().toString().toLowerCase();
        if (n.endsWith(".litematic")) return "litematic";
        if (n.endsWith(".schematic")) return "classic";
        if (n.endsWith(".nbt")) return "vanilla";
        return "sponge";
    }

    /** Find a library schematic by exact filename OR bare name (any supported extension). */
    private static Path findLibraryFile(SchematicService schematics, String base, String raw) throws java.io.IOException {
        Path dir = schematics.dir();
        // 1) exact match on the raw input (may already include the extension)
        Path exact = dir.resolve(raw);
        if (Files.exists(exact)) return exact;
        // 2) bare name + any supported extension
        for (String ext : new String[]{".schem", ".schematic", ".litematic", ".nbt"}) {
            Path f = dir.resolve(base + ext);
            if (Files.exists(f)) return f;
        }
        return null;
    }

    /** v0.25.0 — gold marking shared by both teach paths; NEVER lies about the result. */
    private static String goldNote(LearningSample s, dev.ghbot.builder.VoxelModel model) {
        String g = LearningSample.synthGold(model, 80);
        if (g == null) {
            return " §8(gold skipped — gold exemplars need 1–80 blocks, this model has "
                    + (model == null ? 0 : model.size()) + ")";
        }
        s.goldSpec = g;
        return " §6[gold exemplar — will be injected verbatim into future build prompts]";
    }
}
