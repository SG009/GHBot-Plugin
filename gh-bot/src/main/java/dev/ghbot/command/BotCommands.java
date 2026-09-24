package dev.ghbot.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v0.21.14 — SINGLE SOURCE OF TRUTH for the command/tool catalog.
 * In-game help (`@GH000 help`), the web-console tool sheet (/api/tools), and the
 * AI's system prompt ALL read this same list — so they can never drift apart.
 * (The user hit exactly that: the AI claimed `library` didn't exist because the
 * web sheet was a stale subset.)
 */
public final class BotCommands {

    /** name -> [description, usage] — one authoritative list. */
    public static final Map<String, String[]> CATALOG = new LinkedHashMap<>();

    private static void cmd(String name, String desc, String usage) {
        CATALOG.put(name, new String[]{desc, usage});
    }

    static {
        // ── v0.22.0 — JARVIS-FOR-ADMIN SURFACE. Only 4 pillars:
        //   1 Build · 2 Passive (scan/find/look + edit) · 3 Manage · 4 Interact.
        //   Everything else is SHELVED (kept in code/git, hidden + blocked from use).
        // ── core / info (Interact + Manage) ──
        cmd("help", "All commands + usage", "");
        cmd("chat", "Free conversation with GH-bot", "chat <msg>");
        cmd("view", "Open a build in the browser 3D viewer", "view [jobId] | view file <name>");
        cmd("status", "Server + bot status (TPS/CPU/RAM/tier)", "");
        cmd("cap", "Capability report — what this device can handle", "");
        cmd("device-info", "One-shot stats & capability report", "");
        cmd("provider", "AI backend per bot", "provider list|set <name>");
        cmd("refresh", "Re-learn the server's command catalog", "refresh commands");
        cmd("confirm", "Confirm a blocked systemic command by its token", "confirm <CONF-token>");

        // ── passive eyes (Pillar 2) ──
        cmd("scan", "Terrain summary (ground, heightmap, blocks, water)", "scan <where|here|me> [radius]");
        cmd("look", "What block is here?", "look at <x, y, z|here>");
        cmd("find", "Find blocks of a type", "find <block> [radius]");

        // ── building (Pillar 1) ──
        cmd("plan", "Text-only design preview (no blocks)", "plan <prompt>");
        cmd("build", "Design + stage a build (ghost, then approve)", "build <prompt> [--direct] [at <where>]");
        cmd("edit", "Modify an existing build by instruction", "edit <target|here> <instruction>");
        cmd("schem", "Design + export a schematic", "schem <name> <prompt> [format|all]");
        cmd("schem import", "Import a schematic file into the dataset", "schem import <file> [name]");
        cmd("paste", "Paste a schematic in-world", "paste <file> [where]");
        cmd("library", "Browse the schematic library", "library");
        cmd("export", "Export the staged build as schematics", "export <name> [format|all]");
        cmd("cancel", "Stop the current job", "cancel");

        // ── review (Pillar 1) ──
        cmd("approve", "Approve the staged build", "approve");
        cmd("deny", "Clear/deny the staged build", "deny");
        cmd("redo", "Re-stage the build (cleared + re-staged)", "redo");

        // ── world editing (Pillar 2) ──
        cmd("set", "Set a block at a location (v0.25.0 usage matches the parser)", "set <block> at <x y z|here|me> | set <where> to <block>");
        cmd("replace", "Swap block types in a region", "replace <from> <to> [radius]");
        cmd("terraform", "Terrain edits (smooth/flatten/raise/lower)", "terraform <smooth|flatten|raise|lower> <radius>");
        cmd("undo", "Undo last edit / revert recent edits", "undo [minutes]");

        // ── learning dataset (Pillar 1 quality) — revived from shelved at v0.25.0 (Phase C good-result pack) ──
        cmd("teach", "Add a staged/library build to the learning dataset", "teach <name> [staged] [gold]");
        cmd("dataset", "Browse/manage the build-learning dataset", "dataset list|remove <name>|clear");

        // ── manage server (Pillar 3) ──
        cmd("admin", "Safe config editing (backup+validate+rollback)", "admin <read|set|backup|restore|rollback|reload|menu> …");
        cmd("cmd", "Run server commands as console (multi via ';', systemic need confirm)", "cmd <command> [; command; …]");
        cmd("script", "Preview/run/drop a pending 📎 command-script (.txt)", "script [run|status|drop]");
        cmd("webtoken", "Regenerate/show the web-console login token (v0.23.0)", "webtoken");
        cmd("audit", "Server-console audit: WARN/ERROR digest per plugin + update radar (v0.24.0)", "audit [updates|clear]");
    }

    /**
     * v0.22.0 — commands REMOVED from the admin surface (shelved, kept in code/git).
     * Dispatch rejects them so nothing runs; they are not advertised anywhere.
     *
     * v0.22.1 — FREEZE POLICY (owner decision D4): shelved code is KEPT for revival,
     * NOT deleted. Each shelved registration carries a {@code // SHELVED v0.22.0}
     * marker; the smoke suite's {@code ShelvedSurface} check asserts every name here
     * is (a) hard-blocked at dispatch and (b) absent from CATALOG / toolSheet() /
     * tool help. To revive a command: remove its name from this set, re-add it to
     * CATALOG, and delete its {@code // SHELVED} marker — then re-run the smoke suite.
     */
    public static final java.util.Set<String> SHELVED = java.util.Set.of(
            "design", "image", "memory", "debuglog",
            "where", "save-location", "list-locations", "delete-location",
            "editspec", "schem download", "critique", "animate",
            "add", "deploy", "undeploy", "workers", "avatar", "marker");
    // v0.25.0 — teach + dataset REVIVED from this set (Phase C good-result pack):
    // the learning dataset is how the owner curates gold exemplars for free-tier models.

    /** The web/AI tool sheet — every command with usage, one line each. */
    public static String toolSheet() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : CATALOG.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("- ").append(e.getKey());
            if (e.getValue()[1] != null && !e.getValue()[1].isBlank()) {
                sb.append(' ').append(e.getValue()[1]);
            }
        }
        return sb.toString();
    }
}
