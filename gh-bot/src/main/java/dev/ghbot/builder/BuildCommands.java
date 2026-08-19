package dev.ghbot.builder;

import dev.ghbot.ai.AIClient;
import dev.ghbot.ai.ProviderRegistry;
import dev.ghbot.bot.GHBot;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.log.WIBLogger;
import dev.ghbot.review.GhostService;
import dev.ghbot.schematic.LearningDataset;
import dev.ghbot.terrain.CoordResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Phase 6+7+15 — builder commands: build / plan / cancel / critique.
 * `build <prompt> [--direct] [at <where>]` — design (retrieval-augmented AI
 * or template) → stage as ghost (approve/deny/redo) or build directly.
 */
public final class BuildCommands {

    private BuildCommands() {}

    private static final String SPEC_SYSTEM = """
            You are GH-bot, a Minecraft builder. Convert the user's build request into a
            DesignSpec in EXACTLY this line format (no markdown, no code fence):

            name=Build name
            style=style
            palette=block1,block2,block3
            op=floor cx=0 cz=0 w=11 d=9 y=0 mat=oak_planks
            op=box cx=0 cz=0 w=11 h=5 d=9 y=1 mat=oak_planks
            op=door cx=0 cz=4 y=1 face=south mat=dark_oak_door frame=dark_oak_planks
            op=window_row cx=0 cz=4 y=2 count=3 spacing=2 face=south frame=dark_oak_planks glass=glass
            op=cone cx=0 cz=0 radius=6 height=3 y=6 mat=spruce_planks

            Available ops: floor, box, wall, cylinder, tower, dome, cone, column, ring,
            window_row, door, path, tree, set. Use only valid block names. Keep it small
            (under ~1200 blocks) unless the user asks for big. Reply with ONLY the spec.

            FOR DETAILED / EXACT BUILDS (v0.21.33 — The Commands Man method), instead emit a
            JSON build spec with EXACT block placements so the build matches 100%. Format:
            {
              "name": "My Build",
              "palette": { "0": "minecraft:deepslate_bricks", "1": "minecraft:polished_deepslate" },
              "blocks": [ { "x": 0, "y": 0, "z": 0, "block": "0" }, ... ]
            }
            blocks are relative to the build origin; "block" can be a palette id or a full name.
            Use this when the user describes something specific (a statue, a fortress, a ship…).
            Keep under ~2000 blocks. Reply with ONLY the JSON (no markdown, no code fence).""";

    public static void register(GHBot bot, CommandBridge bridge, BuildService builds,
                                ProviderRegistry providers, GhostService ghosts, LearningDataset dataset,
                                WIBLogger log) {
        CommandRegistry r = bridge.registryOf(bot);

        r.register("build", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            String[] args = ctx.args();
            if (args.length == 0) {
                sender.sendMessage("§eUsage: " + b.id() + " build <prompt> [--direct] [at <where>]");
                return;
            }
            boolean direct = false;
            String where = null;
            List<String> promptParts = new java.util.ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equalsIgnoreCase("--direct")) direct = true;
                else if (a.equalsIgnoreCase("at") && i + 1 < args.length) where = args[++i];
                else promptParts.add(a);
            }
            String prompt = String.join(" ", promptParts);

            Location base = base(sender, b);
            if (base == null) { sender.sendMessage("§cBuild needs a location (stand there or use 'at <where>')."); return; }
            Location target = CoordResolver.resolve(sender, where, base);
            if (target == null) target = base;

            DesignSpec spec = generateSpec(b, providers, dataset, prompt);
            if (spec == null) {
                // v0.21.38 — no template fallback: tell the user the build failed and why
                sender.sendMessage("§c[" + b.id() + "] Build failed — the AI couldn't produce a valid build spec "
                        + "for: \"" + prompt + "\".");
                sender.sendMessage("§7  Check the server console (logs) for the exact reason, or try a shorter, "
                        + "more specific prompt. The AI provider may be overloaded or the request too complex.");
                return;
            }
            sender.sendMessage("§7[" + b.id() + "] Design: §f" + spec.summary());

            boolean animate = b.memory().get("animate") instanceof Boolean ab && ab;
            if (direct) {
                builds.build(b, spec, target, sender);
            } else {
                VoxelModel model = BuildService.toModel(spec);
                if (model.size() == 0) {
                    sender.sendMessage("§c[" + b.id() + "] Design produced no blocks.");
                    return;
                }
                ghosts.stage(b, model, spec.name, target, sender, animate);
            }
        }, CommandRegistry.Meta.of("Design + build in the world (stages a ghost)", "build <prompt> [--direct] [at <where>]"));

        r.register("plan", (b, ctx) -> {
            if (ctx.args().length == 0) { ctx.sender().sendMessage("§eUsage: " + b.id() + " plan <prompt>"); return; }
            String prompt = String.join(" ", ctx.args());
            DesignSpec spec = generateSpec(b, providers, dataset, prompt);
            if (spec == null) {
                ctx.sender().sendMessage("§c[" + b.id() + "] Couldn't design that — the AI failed to produce a valid "
                        + "spec. Try a shorter prompt or check the console log for the reason.");
                return;
            }
            ctx.sender().sendMessage(spec.planText());
            ctx.sender().sendMessage("§7[" + b.id() + "] To build it: " + b.id() + " build " + prompt);
        }, CommandRegistry.Meta.of("Text-only design preview (no blocks)", "plan <prompt>"));

        r.register("critique", (b, ctx) -> {
            var st = ghosts.staged(b);
            if (st == null) {
                ctx.sender().sendMessage("§7[" + b.id() + "] Nothing staged to critique. Use " + b.id() + " build <prompt> first.");
                return;
            }
            String summary = dev.ghbot.edit.EditService.summarize(BuildService.toModel(DesignTemplates.pick(st.specName)));
            AIClient client = providers.resolve(b);
            if (client.id().equals("fallback")) {
                ctx.sender().sendMessage("§7[" + b.id() + "] Critique (fallback): " + st.specName
                        + " (" + st.totalBlocks + " blocks). Connect an AI provider for a real critique.");
                return;
            }
            String prompt = "Critique this build briefly (3-5 bullet points): " + summary + "\nName: " + st.specName;
            try {
                String reply = client.chat("You are GH-bot, a veteran builder giving honest, useful critique. Keep it short.",
                        List.of(new AIClient.ChatMessage("user", prompt)));
                ctx.sender().sendMessage("§e[" + b.id() + "] Critique:\n§7" + reply);
            } catch (Exception e) {
                ctx.sender().sendMessage("§cCritique failed: " + e.getMessage());
            }
        }, CommandRegistry.Meta.of("Ask GH-bot to critique the staged build", "critique"));

        r.register("cancel", (b, ctx) -> {
            boolean had = builds.cancel(b);
            ctx.sender().sendMessage(had ? "§e[" + b.id() + "] Cancelling build…" : "§7[" + b.id() + "] No build running.");
        }, CommandRegistry.Meta.of("Stop the current build", "cancel"));
    }

    /**
     * v0.21.39 — The Commands Man execution contract: if the prompt IS a complete JSON
     * build spec, parse it directly and stage it deterministically. Never send an exact
     * spec back to the AI for re-interpretation (that's where drift creeps in). This
     * fast-path also works with NO AI provider configured.
     */
    public static DesignSpec tryParsePastedSpec(String prompt) {
        if (prompt == null) return null;
        JsonBuildSpec jspec = JsonBuildSpec.parse(prompt);
        if (jspec != null && jspec.isValid()) return DesignSpec.fromJson(jspec);
        return null;
    }

    private static DesignSpec generateSpec(GHBot bot, ProviderRegistry providers,
                                           LearningDataset dataset, String prompt) {
        java.util.logging.Logger plog = org.bukkit.Bukkit.getLogger();
        DesignSpec pasted = tryParsePastedSpec(prompt);
        if (pasted != null) {
            plog.info("[GHBot] build: JSON build spec detected in prompt — staging "
                    + pasted.ops.size() + " exact block(s) directly (no AI interpretation).");
            return pasted;
        }
        AIClient client = providers.resolve(bot);
        if (!client.id().equals("fallback")) {
            try {
                String userPrompt = prompt;
                if (dataset != null && dataset.size() > 0) {
                    var refs = dataset.retrieve(prompt, 3);
                    if (!refs.isEmpty()) {
                        StringBuilder ref = new StringBuilder(
                                "Reference builds from my dataset — match their proportions, materials and density "
                                + "(do NOT copy them exactly):");
                        for (var s : refs) ref.append("\n- ").append(s.compactLine());
                        ref.append("\nUser request: ").append(prompt);
                        userPrompt = ref.toString();
                    }
                }
                // v0.21.36/38 — Ollama constrained JSON + temperature 0 (deterministic, schema-adherent)
                if (client instanceof dev.ghbot.ai.OllamaClient oc) { oc.setJsonMode(true); oc.setTempZero(true); }
                String specText;
                try {
                    specText = client.chat(SPEC_SYSTEM, List.of(new AIClient.ChatMessage("user", userPrompt)));
                } finally {
                    if (client instanceof dev.ghbot.ai.OllamaClient oc2) { oc2.setJsonMode(false); oc2.setTempZero(false); }
                }
                // v0.21.33 — try The Commands Man JSON build spec FIRST (exact blocks = 100% fidelity)
                JsonBuildSpec jspec = JsonBuildSpec.parse(specText);
                if (jspec != null && jspec.isValid()) {
                    dev.ghbot.log.WIBLogger.stamp(); // noop
                    return DesignSpec.fromJson(jspec);
                }
                DesignSpec spec = DesignSpec.parse(specText);
                // v0.21.37 — complex request: DON'T accept weak primitives. Force JSON spec explicitly.
                if (isComplex(prompt)) {
                    // v0.21.38 — research: the schema MUST be in the prompt (not just format:json),
                    // + temperature 0 + a concrete example → weak models produce correct JSON.
                    String forceJson = "You MUST output ONLY a JSON build spec in EXACTLY this schema:\n"
                            + "{\n  \"name\": \"string\",\n  \"palette\": {\"0\": \"minecraft:block\", \"1\": \"minecraft:block\"},\n"
                            + "  \"blocks\": [{\"x\": int, \"y\": int, \"z\": int, \"block\": \"palette-id-or-block-name\"}...]\n}\n"
                            + "Rules: coords relative to origin; block can be a palette id or full name; "
                            + "include EVERY block; no other text.\n"
                            + "Example: {\"name\":\"Tower\",\"palette\":{\"0\":\"minecraft:stone_bricks\"},\n"
                            + "  \"blocks\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"0\"},{\"x\":0,\"y\":1,\"z\":0,\"block\":\"0\"}]}\n"
                            + "Request: " + prompt;
                    if (client instanceof dev.ghbot.ai.OllamaClient oc3) { oc3.setJsonMode(true); oc3.setTempZero(true); }
                    try {
                        specText = client.chat(SPEC_SYSTEM, List.of(new AIClient.ChatMessage("user", forceJson)));
                    } finally {
                        if (client instanceof dev.ghbot.ai.OllamaClient oc4) { oc4.setJsonMode(false); oc4.setTempZero(false); }
                    }
                    jspec = JsonBuildSpec.parse(specText);
                    if (jspec != null && jspec.isValid()) return DesignSpec.fromJson(jspec);
                    plog.warning("[GHBot] complex build: forced JSON failed — using DesignSpec "
                            + spec.name + " (" + spec.ops.size() + " ops). AI said: "
                            + dev.ghbot.agent.TokenCompress.compress(specText, 200).text());
                    if (spec.isValid()) return spec;
                    return DesignTemplates.pick(prompt);
                }
                if (spec.isValid()) return spec;
                // v0.21.38 — LOG the failure reason (no silent fallback)
                plog.warning("[GHBot] generateSpec: AI reply was neither valid JSON nor DesignSpec"
                        + " (provider=" + client.id() + ", len=" + specText.length() + ") — "
                        + dev.ghbot.agent.TokenCompress.compress(specText, 300).text());
            } catch (Exception e) {
                plog.warning("[GHBot] generateSpec exception from " + client.id() + ": "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        } else {
            plog.warning("[GHBot] generateSpec: no AI provider configured (fallback) — build failed.");
        }
        return null;   // v0.21.38 — NO template fallback. Build fails clearly instead of staging a template.
    }

    /** Heuristic: a request is "complex" if it lists multiple features/materials — needs JSON for fidelity. */
    private static boolean isComplex(String prompt) {
        if (prompt == null) return false;
        String p = prompt.toLowerCase();
        int features = 0;
        for (String kw : new String[]{"tower", "walls", "gate", "keep", "beacon", "statue", "dragon",
                "fortress", "castle", "ship", "wings", "dome", "spire", "courtyard", "portcullis",
                "wingspan", "horns", "obsidian", "deepslate", "basalt", "palace", "cathedral",
                "with ", " and ", " featuring ", " plus "}) {
            if (p.contains(kw)) features++;
        }
        return features >= 2;
    }

    private static Location base(CommandSender sender, GHBot bot) {
        if (sender instanceof Player p) return p.getLocation();
        Object o = bot.memory().get("terrain.origin");
        if (o instanceof String s) {
            try {
                String[] p = s.split(",");
                if (p.length == 3 && sender.getServer() != null && !sender.getServer().getWorlds().isEmpty()) {
                    var w = sender.getServer().getWorlds().get(0);
                    return new Location(w, Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
                }
            } catch (NumberFormatException ignored) {}
        }
        try {
            if (sender.getServer() != null && !sender.getServer().getWorlds().isEmpty())
                return sender.getServer().getWorlds().get(0).getSpawnLocation();
        } catch (Throwable ignored) {}
        return null;
    }
}
