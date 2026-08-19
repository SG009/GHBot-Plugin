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
        // ── core / info ──
        cmd("help", "All commands + usage", "");
        cmd("chat", "Free conversation with GH-bot", "chat <msg>");
        cmd("design", "Guided conversational design session", "design <topic|answer|done>");
        cmd("view", "Open a build in the browser 3D viewer", "view [jobId] | view file <name>");
        cmd("image", "Toggle AI concept-image preview", "image <on|off>");
        cmd("status", "Server + bot status (TPS/CPU/RAM/tier)", "");
        cmd("cap", "Capability report — what this device can handle", "");
        cmd("device-info", "One-shot stats & capability report", "");
        cmd("memory", "Manage session memory", "memory clear");
        cmd("debuglog", "Toggle per-bot debug logs", "debuglog <show|hide>");
        cmd("provider", "AI backend per bot", "provider list|set <name>");
        cmd("refresh", "Re-learn the server's command catalog", "refresh commands");
        cmd("confirm", "Confirm a blocked systemic command by its token", "confirm <CONF-token>");

        // ── terrain eyes ──
        cmd("scan", "Terrain summary (ground, heightmap, blocks, water)", "scan <where|here|me> [radius]");
        cmd("look", "What block is here?", "look at <x, y, z|here>");
        cmd("find", "Find blocks of a type", "find <block> [radius]");
        cmd("where", "Coordinates of a saved location", "where <name>");
        cmd("save-location", "Save the current spot as a named location", "save-location <name>");
        cmd("list-locations", "All saved locations", "list-locations");
        cmd("delete-location", "Remove a saved location", "delete-location <name>");

        // ── building ──
        cmd("plan", "Text-only design preview (no blocks)", "plan <prompt>");
        cmd("build", "Design + stage a build (ghost, then approve)", "build <prompt> [--direct] [at <where>]");
        cmd("edit", "Modify an existing build by instruction", "edit <target|here> <instruction>");
        cmd("editspec", "Preview the edit ops an instruction would generate", "editspec <target> <instruction>");
        cmd("schem", "Design + export a schematic", "schem <name> <prompt> [format|all]");
        cmd("schem download", "Download a schematic from the internet", "schem download <name> <url>");
        cmd("schem import", "Import a schematic file into the dataset", "schem import <file> [name]");
        cmd("paste", "Paste a schematic in-world", "paste <file> [where]");
        cmd("library", "Browse the schematic library", "library");
        cmd("export", "Export the staged build as schematics", "export <name> [format|all]");
        cmd("teach", "Add a library file or staged build to the dataset", "teach <name> [staged]");
        cmd("dataset", "Manage the learning dataset", "dataset list|remove <name>|clear");
        cmd("critique", "Ask GH-bot to critique the staged build", "critique");

        // ── review ──
        cmd("approve", "Approve the staged build", "approve");
        cmd("deny", "Clear/deny the staged build", "deny");
        cmd("redo", "Re-stage the build (cleared + re-staged)", "redo");
        cmd("animate", "Toggle cinematic build pass", "animate on|off");

        // ── world editing ──
        cmd("set", "Set a region of blocks", "set <block> <radius>");
        cmd("replace", "Swap block types in a region", "replace <from> <to> [radius]");
        cmd("terraform", "Terrain edits (smooth/flatten/raise/lower)", "terraform <smooth|flatten|raise|lower> <radius>");
        cmd("undo", "Undo last edit / revert recent edits", "undo [minutes]");
        cmd("cancel", "Stop the current job", "cancel");

        // ── admin ──
        cmd("admin", "Safe config editing (backup+validate+rollback)", "admin <read|set|backup|restore|rollback|reload|menu> …");
        cmd("cmd", "Run server commands as console (multi via ';', systemic need confirm)", "cmd <command> [; command; …]");
        cmd("add", "Natural-language admin task (NPC, sign, …)", "add <thing> at <where>");

        // ── crews / avatar ──
        cmd("deploy", "Deploy a new worker bot at runtime", "deploy <id> [role]");
        cmd("undeploy", "Remove a worker bot", "undeploy <id>");
        cmd("workers", "List all bots/workers and their roles", "workers");
        cmd("avatar", "Toggle the Enderman avatar", "avatar on|off");
        cmd("marker", "Place a waypoint marker", "marker <name>");
    }

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
