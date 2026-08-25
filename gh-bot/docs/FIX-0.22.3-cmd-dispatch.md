# FIX v0.22.3 — cmd dispatch broken on Paper 1.21 (FeedbackForwarding route) + CONF loop + admin read + scan y<0

Date: 2026-08-25 · Trigger: owner's live batch-test evidence (`admin/commands/chat-console/latest.txt` from their Paper 1.21.11-132 Termux server running GHBot 0.22.2)

## Symptoms (owner evidence)

| Evidence | What the owner saw |
|---|---|
| chat-console.txt | `cmd plugins`, `cmd version`, `cmd pl`, `cmd bukkit:plugins`, `cmd about`, `cmd help`, `cmd ?` → **ALL "✗ failed"** |
| chat-console.txt | `confirm CONF-…` → `confirmed but command failed: stop` + a **fresh CONF token minted every time** (5-token loop, `stop` never ran) |
| chat-console.txt | `admin read server.properties` / `admin read bukkit.yml` → **"Path escapes server dir."** |
| chat-console.txt | `scan 20 at 0 -1 0` → `y0..319 — 0/537920 blocks` (blind below y=0; owner's hub ground is at y=-1) |
| latest.txt | **zero exceptions logged** — every failure was swallowed silently |
| commands.txt | audit even logged `stop → ok` for a BLOCKED dispatch |

## Root causes (verified against Paper source + a local live repro)

### R1 — Paper 1.21 routes ALL dispatch through the vanilla listener converter
`CraftServer.dispatchCommand` (paper-server 1.21.11, line 927) calls
`VanillaCommandWrapper.getListener(rawSender)` **before parsing** — and `getListener`
throws `IllegalArgumentException("Cannot make <sender> a vanilla command listener")`
for every plain custom `CommandSender` (accepted types: CraftEntity, BlockCommandSender,
RemoteConsoleCommandSender, ConsoleCommandSender, ProxiedCommandSender, and Paper's own
`FeedbackForwardingSender` — anything else ⇒ throw).

v0.22.2 put BOTH the custom-sender dispatch AND the console fallback inside one
`try/catch`, so the throw from the first attempt also skipped the fallback → every
command ended FAILED with the exception swallowed (`catch (Throwable t) { ok = false; }`).
That also explains 0.22.1's "[output went to the server console]": the custom dispatch
threw there too; the old two-try structure still ran the console fallback.

**Proven empirically:** standalone Probe plugin on a sandbox-local Paper 1.21.11-132
(exact owner build `c5eb079`): console sender → `true` for plugins/version/help/gh;
custom sender → `IllegalArgumentException` at `VanillaCommandWrapper.getListener:105`
for every line.

**Fix — Paper's supported hook.** `getListener` accepts Paper's
`io.papermc.paper.commands.FeedbackForwardingSender` and calls `asVanilla()` on it.
That class (paper-server, not paper-api — reflected at runtime, zero new deps) funnels
**all** feedback into one `Consumer<Component>`: legacy String (deserialized via
LegacyComponentSerializer), Adventure Components, and vanilla `sendSystemMessage`
(via its inner `Source implements CommandSource` whose `getBukkitSender()` returns the
sender itself). OWNER vanilla permission level. New class:
`dev.ghbot.command.FeedbackForwarder` (feature-detect once; null → console fallback for
Spigot/old Paper/headless). Dispatch failures now produce a reason note instead of
silence, and FAILED output renders it (`✗ failed: stip — unknown to the server (…)`).

### R2 — CONFIRM re-applied the systemic guard
v0.22.2's `confirm()` routed the confirmed line back through the guarded capture,
which re-blocked it, minted a NEW token and returned BLOCKED ≈ "failed" → infinite loop,
systemic commands un-runnable. **Fix:** `capture(bot, line, bypassGuard = true)` used
 ONLY by `confirm()` — the CONF-… token IS the confirmation. Guard untouched everywhere else.

### R3 — AdminService compared absolute-path against relative root
`detectServerRoot()` can return a **relative or empty** path (Bukkit's world container is
`File(".")`; on the owner's multi-world setup detection path (1) returns it verbatim).
`resolveFile` then tested `absolute.startsWith(relative)` → always false →
"Path escapes server dir." for every whitelisted server file. **Fix:**
`toAbsoluteRoot()` (toAbsolutePath+normalize) applied once at construction;
smoke-pinned. Live probe: `worldContainer=[.]`, old code threw, new code reads
`server.properties` fine.

### R4 — scan column clamped to pre-1.18 world height
`TerrainScanner.scan`/`scanSpec` used `Math.max(world.getMinHeight(), 0)` — blind to
everything below y=0 (1.18+ worlds go to -64; owner's hub bedrock layer included).
**Fix:** `columnMinY(w.getMinHeight())` (smoke-pinned). Live contrast on flat world:
OLD minY=0 → 0 non-air; NEW minY=-64 → **6724/645504 non-air**.

## Also shipped in v0.22.3

- **Audit single-point + truth:** dispatch auditing moved INTO `CmdOutputCapture.capture()`
  (covers AI-tool, web /cmd, in-game and confirm paths — previously only the AI path
  audited, and BLOCKED was logged as "ok").
- **Note-preservation fix:** the file-routing rebuild in `capture()` dropped the
  BLOCKED/FAILED `note` from the final CmdOutput (found via the live `stip` repro).
- **`/gh <botcommand>` async note:** bot-registry subs (`g h confirm`, `gh memory`, …)
  run on the bridge async pool BY DESIGN (AI must never block main) — web `/cmd` capture
  therefore can't include their reply text (it races the HTTP response). Actions still
  execute + audit. Chat AUTO-TOOL and in-game surfaces are synchronous and unaffected.

## Live validation matrix (local Paper 1.21.11-132, exact owner build)

Booted in-sandbox (fill.papermc.io v3 API — note: api v2 is sunset), GHBot web /cmd:

| Test | 0.22.2 | 0.22.3 |
|---|---|---|
| `cmd plugins` | ✗ failed | ✓ `Server Plugins (1): GHBot` captured |
| `cmd help` / `bukkit:plugins` | ✗ failed | ✓ full output captured |
| `cmd list` (pure vanilla) | ✗ failed | ✓ `There are 0 of a max of 2 players online:` |
| `cmd stip` | ✗ (silent) | ✗ + `unknown to the server (try refresh / catalog …)` |
| `whitelist off` → `confirm` | re-mint loop | ✓ executed (audit `whitelist off → ok`) |
| `stop` → `confirm` | never ran | ✓ server halted end-to-end |
| `admin resolveFile/read` | "Path escapes server dir." | ✓ reads `server.properties` |
| `scan` flat world @ y=-60 | 0/537920 | 6724/645504 (minY=-64) |
| `cmd plugins;list` (multi) | ✗✗ | ✓ `2 ran · 0 blocked · 0 failed` + per-line sections |

Headless smoke: **471/471 PASS** (+17 vs 0.22.2), incl. pins for: CONF never re-mints,
token one-shot, audit-once-per-dispatch, note preservation, root anchoring, column min-Y.

## Sources

- `PaperMC/Paper@ver/1.21.11` `CraftServer.dispatchCommand` (calls getListener for every dispatch)
- `PaperMC/Paper@ver/1.21.11` `VanillaCommandWrapper.getListener` (throw + the FeedbackForwardingSender branch)
- `PaperMC/Paper@ver/1.21.11` `io/papermc/paper/commands/FeedbackForwardingSender.java` (Consumer feedback + Source CommandSource)
- `paper-server/patches/sources/net/minecraft/commands/CommandSource.java.patch` (`getBukkitSender(stack)` hook)
- PaperMC fill API v3 for the server binary (api v2 sunset; build 132 == owner's `c5eb079`)
