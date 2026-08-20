package dev.ghbot.edit;

import dev.ghbot.ai.AIClient;
import dev.ghbot.ai.ProviderRegistry;
import dev.ghbot.bot.GHBot;
import dev.ghbot.builder.DesignSpec;
import dev.ghbot.builder.VoxelModel;
import dev.ghbot.command.CommandBridge;
import dev.ghbot.command.CommandRegistry;
import dev.ghbot.log.WIBLogger;
import dev.ghbot.terrain.CoordResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 10 — Structure Editing commands: `edit <target> <instruction>`.
 * AI produces an EditSpec (ops) from the instruction + region summary;
 * a template parser covers common instructions without AI.
 */
public final class EditCommands {

    private EditCommands() {}

    private static final String EDIT_SYSTEM = """
            You are GH-bot, editing an existing Minecraft build. You are given a region summary, a set of
            structural anchors, and an instruction. Output ONLY DesignSpec-style op lines (no code fence, no
            markdown) that MODIFY the structure. Ops are relative to the region origin (0,0,0 = the origin
            block). Anchors are given in region-relative coordinates — use them instead of guessing: e.g.
            place the roof over roof_center, build onto foundation_base, align windows to north_wall.
            Use:
            op=set x=0 y=0 z=0 mat=stone_bricks
            op=box cx=0 cz=0 w=5 h=3 d=5 y=0 mat=oak_planks
            op=column x=0 z=0 y0=0 y1=4 mat=stone_bricks
            op=cone cx=0 cz=0 radius=4 height=3 y=5 mat=spruce_planks
            op=floor cx=0 cz=0 w=7 d=7 y=0 mat=cobblestone
            op=window_row cx=0 cz=0 y=2 count=3 spacing=2 face=north frame=dark_oak_planks glass=glass
            op=door cx=0 cz=0 y=0 face=south mat=dark_oak_door frame=dark_oak_planks
            op=tree x=0 z=0 y=1 height=4 log=oak_log leaves=oak_leaves
            op=path x0=0 z0=0 x1=5 z1=0 y=0 mat=gravel
            Reply with ONLY the ops.""";

    public static void register(GHBot bot, CommandBridge bridge, EditService edits,
                                ProviderRegistry providers, WIBLogger log) {
        CommandRegistry r = bridge.registryOf(bot);

        r.register("edit", (b, ctx) -> {
            CommandSender sender = ctx.sender();
            if (ctx.args().length < 2) {
                sender.sendMessage("§eUsage: " + b.id() + " edit <target|here> <instruction>");
                return;
            }
            // v0.21.32 — support "edit <x> <y> <z> <instruction>" (coords as the first 3 args)
            String targetArg;
            String instruction;
            if (ctx.args().length >= 4 && ctx.args()[0].matches("-?\\d+")
                    && ctx.args()[1].matches("-?\\d+") && ctx.args()[2].matches("-?\\d+")) {
                targetArg = ctx.args()[0] + "," + ctx.args()[1] + "," + ctx.args()[2];
                instruction = String.join(" ", java.util.Arrays.copyOfRange(ctx.args(), 3, ctx.args().length));
            } else {
                targetArg = ctx.args()[0];
                instruction = String.join(" ", java.util.Arrays.copyOfRange(ctx.args(), 1, ctx.args().length));
            }

            Location base = base(sender, b);
            if (base == null) { sender.sendMessage("§cEdit needs a location."); return; }
            Location target = CoordResolver.resolve(sender, targetArg, base);
            if (target == null) target = CoordResolver.resolvePlayer(targetArg);
            if (target == null) {
                // v0.22.1 — natural-language phrasing "edit the dragon's wings": the
                // first word is a determiner, not a location. Fall back to "here" and
                // keep the whole phrase as the instruction instead of a bare error.
                if (targetArg.matches("(?i)(the|a|an|that|this|it|those|these|some|my|our)")) {
                    target = base;
                    instruction = targetArg + " " + instruction;
                } else {
                    sender.sendMessage("§cCouldn't resolve \"" + targetArg + "\".");
                    return;
                }
            }

            int radius = 15;
            var rm = java.util.regex.Pattern.compile("radius\\s+(\\d+)").matcher(instruction);
            if (rm.find()) radius = Math.max(2, Math.min(60, Integer.parseInt(rm.group(1))));

            // snapshot + summary
            VoxelModel region = edits.snapshot(target, radius);
            String summary = EditService.summarize(region);
            sender.sendMessage("§7[" + b.id() + "] Target region: " + summary);

            // state-drift guard (v0.21): fingerprint the live region NOW, so we can
            // detect anything that moved between planning and applying
            long fpBefore = fp(target, radius);

            // produce EditSpec: AI or template
            DesignSpec spec = generateEditSpec(b, providers, summary, instruction, region);
            if (!spec.isValid()) {
                sender.sendMessage("§c[" + b.id() + "] Couldn't figure out an edit for that. "
                        + "Try: replace <from> with <to>, add columns, add a roof, etc.");
                return;
            }

            // region changed while the AI was planning? warn + re-scan before applying
            long fpNow = fp(target, radius);
            if (fpBefore != fpNow) {
                sender.sendMessage("§e[" + b.id() + "] Region changed while planning (blocks moved) "
                        + "— re-scanning before apply…");
                region = edits.snapshot(target, radius);
                summary = EditService.summarize(region);
                spec = generateEditSpec(b, providers, summary, instruction, region);
                if (!spec.isValid()) {
                    sender.sendMessage("§c[" + b.id() + "] Edit no longer fits the changed region — "
                            + "give a new instruction based on: " + summary);
                    return;
                }
            }

            sender.sendMessage("§7[" + b.id() + "] Edit plan: " + spec.planText());
            sender.sendMessage("§7[" + b.id() + "] Applying…");
            Location origin = target.clone();
            origin.setX(target.getBlockX()); origin.setY(target.getBlockY()); origin.setZ(target.getBlockZ());
            edits.apply(new EditService.GHBotRef() { public GHBot bot() { return b; } },
                    spec, origin, sender, () -> {});
        }, CommandRegistry.Meta.of("Modify an existing build by instruction", "edit <target|here> <instruction>"));

        r.register("editspec", (b, ctx) -> {
            // debug: show the ops an instruction would generate
            if (ctx.args().length < 2) { ctx.sender().sendMessage("§eUsage: " + b.id() + " editspec <target> <instruction>"); return; }
            String instruction = String.join(" ", java.util.Arrays.copyOfRange(ctx.args(), 1, ctx.args().length));
            var spec = templateEditSpec(instruction, new VoxelModel());
            ctx.sender().sendMessage(spec.planText());
        }, CommandRegistry.Meta.of("Preview the edit ops for an instruction", "editspec <target> <instruction>"));
    }

    private static DesignSpec generateEditSpec(GHBot bot, ProviderRegistry providers,
                                               String summary, String instruction, VoxelModel region) {
        AIClient client = providers.resolve(bot);
        if (!client.id().equals("fallback")) {
            try {
                String prompt = "Region summary: " + summary
                        + "\nStructural anchors: " + EditService.anchorLine(region)
                        + "\nInstruction: " + instruction;
                String specText = client.chat(EDIT_SYSTEM, List.of(new AIClient.ChatMessage("user", prompt)));
                DesignSpec spec = DesignSpec.parse(specText);
                if (spec.isValid()) return spec;
            } catch (Exception ignored) {}
        }
        return templateEditSpec(instruction, region);
    }

    /** Fingerprint the live world region around a target (drift guard). */
    private static long fp(Location target, int radius) {
        org.bukkit.World w = target.getWorld();
        if (w == null) return 0L;
        int x = target.getBlockX(), y = target.getBlockY(), z = target.getBlockZ();
        return RegionFingerprint.of(w, x - radius, y - radius, z - radius,
                x + radius, y + radius, z + radius);
    }

    /** Region bounding box as int[]{minX,minY,minZ,maxX,maxY,maxZ}, or null if empty. */
    private static int[] bbox(VoxelModel region) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Map.Entry<Long, String> e : region.entries()) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        return minX == Integer.MAX_VALUE ? null : new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Template parser for common edit instructions (no AI needed). */
    public static DesignSpec templateEditSpec(String instruction, VoxelModel region) {
        DesignSpec s = new DesignSpec();
        s.name = "template edit";
        String ins = instruction.toLowerCase();
        List<Map.Entry<Long, String>> voxels = new ArrayList<>();
        region.entries().forEach(voxels::add);

        // "replace <from> with <to>" — swap every matching voxel in the structure
        if (ins.contains("replace") && ins.contains("with")) {
            String[] parts = ins.split("\\s+");
            Material from = null, to = null;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("replace") && i + 1 < parts.length) from = match(parts[i + 1]);
                if (parts[i].equals("with") && i + 1 < parts.length) to = match(parts[i + 1]);
            }
            if (from != null && to != null) {
                for (Map.Entry<Long, String> e : voxels) {
                    if (e.getValue().equals(from.name().toLowerCase())) {
                        s.ops.add(new DesignSpec.Op("set", Map.of(
                                "x", String.valueOf(VoxelModel.xOf(e.getKey())),
                                "y", String.valueOf(VoxelModel.yOf(e.getKey())),
                                "z", String.valueOf(VoxelModel.zOf(e.getKey())),
                                "mat", to.name().toLowerCase())));
                    }
                }
                return s;
            }
        }
        // "remove <block>" — set matching voxels to air
        if (ins.startsWith("remove ") || ins.contains("remove ")) {
            String[] parts = ins.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("remove") && i + 1 < parts.length) {
                    Material m = match(parts[i + 1]);
                    if (m != null) {
                        for (Map.Entry<Long, String> e : voxels) {
                            if (e.getValue().equals(m.name().toLowerCase())) {
                                s.ops.add(new DesignSpec.Op("set", Map.of(
                                        "x", String.valueOf(VoxelModel.xOf(e.getKey())),
                                        "y", String.valueOf(VoxelModel.yOf(e.getKey())),
                                        "z", String.valueOf(VoxelModel.zOf(e.getKey())),
                                        "mat", "air")));
                            }
                        }
                        return s;
                    }
                }
            }
        }
        // bbox-aware geometry (v0.21): ops are anchored to the actual structure
        int[] bb = bbox(region);
        int w = bb == null ? 0 : bb[3] - bb[0] + 1;
        int h = bb == null ? 0 : bb[5] - bb[2] + 1;
        int d = bb == null ? 0 : bb[4] - bb[1] + 1;

        // "add <n> <block> column(s)" at the structure's real corners
        if (ins.contains("column") || ins.contains("pillar")) {
            Material m = null;
            for (String wd : ins.split("\\s+")) {
                Material mm = match(wd);
                if (mm != null && mm != Material.AIR) { m = mm; break; }
            }
            String mat = m == null ? "stone_bricks" : m.name().toLowerCase();
            int top = bb == null ? 5 : Math.max(3, Math.min(12, h + 2));
            if (bb == null) {
                s.ops.add(new DesignSpec.Op("column", Map.of("x", "0", "z", "0", "y0", "0", "y1", "5", "mat", mat)));
                s.ops.add(new DesignSpec.Op("column", Map.of("x", "1", "z", "0", "y0", "0", "y1", "5", "mat", mat)));
                s.ops.add(new DesignSpec.Op("column", Map.of("x", "0", "z", "1", "y0", "0", "y1", "5", "mat", mat)));
            } else {
                int cx0 = bb[0], cz0 = bb[1], cx1 = bb[3], cz1 = bb[4];
                s.ops.add(new DesignSpec.Op("column", Map.of("x", String.valueOf(cx0), "z", String.valueOf(cz0),
                        "y0", String.valueOf(bb[2]), "y1", String.valueOf(top), "mat", mat)));
                s.ops.add(new DesignSpec.Op("column", Map.of("x", String.valueOf(cx0), "z", String.valueOf(cz1),
                        "y0", String.valueOf(bb[2]), "y1", String.valueOf(top), "mat", mat)));
                s.ops.add(new DesignSpec.Op("column", Map.of("x", String.valueOf(cx1), "z", String.valueOf(cz0),
                        "y0", String.valueOf(bb[2]), "y1", String.valueOf(top), "mat", mat)));
                s.ops.add(new DesignSpec.Op("column", Map.of("x", String.valueOf(cx1), "z", String.valueOf(cz1),
                        "y0", String.valueOf(bb[2]), "y1", String.valueOf(top), "mat", mat)));
            }
            return s;
        }
        // "add a roof" — cone sized to the structure's roof_center
        if (ins.contains("roof")) {
            String mat = ins.contains("spruce") ? "spruce_planks" : "dark_oak_planks";
            int cx = bb == null ? 0 : (bb[0] + bb[3]) / 2;
            int cz = bb == null ? 0 : (bb[1] + bb[4]) / 2;
            int radius = bb == null ? 5 : Math.max(2, Math.max(w, d) / 2);
            int height = bb == null ? 3 : Math.max(2, Math.min(6, Math.max(w, d) / 2));
            int y = bb == null ? 6 : bb[5] + 1;
            s.ops.add(new DesignSpec.Op("cone", Map.of("cx", String.valueOf(cx), "cz", String.valueOf(cz),
                    "radius", String.valueOf(radius), "height", String.valueOf(height),
                    "y", String.valueOf(y), "mat", mat)));
            return s;
        }
        // "add a window" — window row on the face the user asked for
        if (ins.contains("window")) {
            String face = ins.contains("north") ? "north" : ins.contains("east") ? "east"
                    : ins.contains("west") ? "west" : ins.contains("south") ? "south" : "north";
            int cx = bb == null ? 0 : (bb[0] + bb[3]) / 2;
            int cz = bb == null ? 0 : (bb[1] + bb[4]) / 2;
            int y = bb == null ? 2 : Math.min(bb[2] + 2, Math.max(bb[2] + 1, bb[5] - 2));
            int count = bb == null ? 3 : Math.max(2, Math.min(6, Math.max(w, d) / 3));
            s.ops.add(new DesignSpec.Op("window_row", Map.of("cx", String.valueOf(cx), "cz", String.valueOf(cz),
                    "y", String.valueOf(y), "count", String.valueOf(count), "spacing", "2",
                    "face", face, "frame", "dark_oak_planks", "glass", "glass")));
            return s;
        }
        // "add a door" — on the requested face, at foundation level
        if (ins.contains("door")) {
            String face = ins.contains("north") ? "north" : ins.contains("east") ? "east"
                    : ins.contains("west") ? "west" : "south";
            int cx = bb == null ? 0 : (bb[0] + bb[3]) / 2;
            int cz = bb == null ? 0 : (bb[1] + bb[4]) / 2;
            int y = bb == null ? 0 : bb[2];
            s.ops.add(new DesignSpec.Op("door", Map.of("cx", String.valueOf(cx), "cz", String.valueOf(cz),
                    "y", String.valueOf(y), "face", face, "mat", "dark_oak_door", "frame", "dark_oak_planks")));
            return s;
        }
        // "add a tree" — at the structure center, on the foundation
        if (ins.contains("tree")) {
            int x = bb == null ? 0 : (bb[0] + bb[3]) / 2;
            int z = bb == null ? 0 : (bb[1] + bb[4]) / 2;
            int y = bb == null ? 1 : bb[2] + 1;
            s.ops.add(new DesignSpec.Op("tree", Map.of("x", String.valueOf(x), "z", String.valueOf(z),
                    "y", String.valueOf(y), "height", "4")));
            return s;
        }
        return s; // invalid
    }

    private static Material match(String s) {
        if (s == null) return null;
        String clean = s.toLowerCase().replace("minecraft:", "").replace(",", "");
        try { return Material.matchMaterial(clean); } catch (Exception e) { return null; }
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
