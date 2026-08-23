# PLAN — Pillar 3: `cmd` output capture into the chat-console (v0.22.2 proposal)

Status: **research complete — awaiting owner GO + decisions D1–D5 before any code changes.**
Prepared: 2026-08-23 (Asia/Jakarta) · Base: v0.22.1 + doc-sync (`c6139c2`) · Deep-researched (external sources cited inline).

---

## 0. TL;DR

Today's human-facing `cmd` paths report only "✓ ran". The AI-facing path *does* capture output —
but through a proxy sender that **silently drops every Adventure-Component message** (verified at
Adventure source level). So on Paper 1.21, output from modern, Component-native plugins —
**LuckPerms (the Milestone-2 runbook target), DeluxeMenus, and vanilla feedback** — is at best
partially captured and often shows the placeholder `"[output went to the server console — not
capturable by the tool]"`, even though the command replied to the sender.

Fix: (1) make the proxy sender capture **all three messaging APIs** (legacy string, Adventure
Component, bungee BaseComponent) — zero new dependencies; (2) add a **JUL session handler** around
the dispatch to also catch plugins that *log* instead of *replying* — JDK-only, smoke-testable;
(3) route **all four** `cmd` paths through one capture service, with eyes-style routing
(full output → `logs/cmd/*.log`, bounded inline in the reply).

The often-suggested **Log4j2 appender is dependency-blocked** in this project (§3.2) — log4j-core is
on no build path. The JUL approach covers the dominant real-world case with zero deps.

---

## 1. Current state (verified in code at v0.22.1)

Four ways to run a server command:

| # | Path | Code | Captures output today? |
|---|---|---|---|
| 1 | Web chat, imperative (`cmd give …`) → AutoTools → AI tool | `ChatService:204` → `GHBotPlugin:288` → `dispatchCaptured` | **Partially** (see §2) |
| 2 | AI `⟦tool:cmd⟧` marker | `GHBotPlugin:288` → `dispatchCaptured` | **Partially** (see §2) |
| 3 | Web `/cmd <line>` API route | `WebStatusServer:392` → `dispatchGuardedMany` → console sender | **No** — "✓ line" summary only |
| 4 | In-game `@GH000 cmd …` | `CommandLearningCommands:30` → `dispatchGuardedMany` → console sender | **No** — summary only |

Existing capture (`CommandLearning.dispatchCaptured`, v0.21.26/29):
- guardrails first (sensitive → CONF token, never executed) ✅ keep
- dispatches via `ToolBridge.Capture` proxy sender, fallback to `Bukkit.getConsoleSender()` when the proxy dispatch returns false ✅ keep
- if nothing was captured: `"[output went to the server console — not capturable by the tool]"` ← **often wrong diagnosis** (§2)

`ToolBridge.Capture` today overrides only the legacy `sendMessage(String)` family and returns
`null` from `spigot()` (NPE risk for any plugin calling `sender.spigot().sendMessage(...)`).

---

## 2. Root-cause finding: the proxy sender drops all Adventure Components (verified)

Paper 1.21's `CommandSender` extends Adventure `Audience`. On `Audience`, **every** message entry
point (`sendMessage(Component)`, `sendPlainMessage`, `sendRichMessage`, typed `sendMessage(…)`)
is a *default* method that funnels into terminal defaults whose bodies are **empty**:

```java
// adventure-api Audience.java (4.26.x branch), the terminal overload everything funnels into:
default void sendMessage(final @NotNull Identity source, final @NotNull Component message,
                         final @NotNull MessageType type) {
    // implementation required        ← NO-OP
}
```
Source: https://github.com/KyoriPowered/adventure/blob/main/4/api/src/main/java/net/kyori/adventure/audience/Audience.java (fetched 2026-08-23); API surface confirmed in
Paper 1.21 javadoc: https://jd.papermc.io/paper/1.21/org/bukkit/command/CommandSender.html
(same default methods marked `default`).

**Consequence:** `ToolBridge.Capture` captures only plugins that call the *legacy*
`sendMessage(String)`. Any Adventure-native plugin — LuckPerms, DeluxeMenus, most modern plugins,
and Paper-native vanilla feedback paths — has its reply **silently discarded**, producing the
misleading "output went to the server console" message. (The v0.21.26 note that capture "worked
for the schematic list" is consistent: GHBot's own catalog commands reply with legacy strings.)

This matters beyond cosmetics: **Milestone 2's runbook is LuckPerms rank setup via `cmd`** — with
today's capture, LP's confirmations would be invisible to the Jarvis loop.

## 3. Capture-surface options (researched)

### 3.1 Proxy CommandSender (upgrade existing) — chosen basis
Dispatch on the main thread (already enforced via `MainThread.call`) with a sender that overrides
**all three messaging APIs**: legacy string, Adventure terminal (`Identity, Component, MessageType`
→ `PlainTextComponentSerializer.plainText()`), and `spigot()` returning a real `Spigot` subclass
that captures bungee `BaseComponent` (`.toPlainText()`).
- ✅ Attribution is exact: output belongs to *this* dispatch.
- ✅ Zero new deps: `adventure-text-serializer-plain` **4.26.1** and `bungeecord-chat` are both
  already in the paper-api compile closure (verified in the resolved Gradle cache here).
- ✅ Fully smoke-testable headlessly (existing smoke instantiates senders directly).
- ⚠️ Blind spot: plugins that `plugin.getLogger().info(...)` *instead of* replying to the sender.

### 3.2 Log4j2 appender (DiscordSRV pattern) — dependency-blocked, documented not built
The established way to capture *all* console output is a programmatic Log4j2 appender on the root
logger (DiscordSRV's `DiscordConsoleAppender`; pattern referenced in
https://www.spigotmc.org/threads/how-to-get-all-console-output.299146/). Paper bundles log4j-core
**at runtime**, but compiling against it requires `compileOnly org.apache.logging.log4j:log4j-core`:
- ❌ **not in paper-api's compile closure** (only `log4j-api` 2.24.1 is; verified in the cache).
- ❌ **`sandbox-build.sh` fallback can't get it**: GitHub releases of log4j2 ship **no binary
  assets** (checked `apache/logging-log4j2` release `rel/2.24.1` via the GitHub API) and the
  ZoneGuard committed dependency cache contains **no log4j-core** (checked its git tree).
- ❌ Adding the dep would make the GitHub/PyPI-only sandbox path fail to compile — a regression of
  a documented sandbox constraint. Reflective `Proxy`-based appenders avoid the dep but are
  untestable headlessly — against the project's "never claim without smoke" doctrine.
- Verdict: **rejected for v0.22.2.** Revisit if a full console-tail feed is wanted later (D2).

### 3.3 JUL session handler — chosen complement
Plugins overwhelmingly log via `plugin.getLogger()` = **java.util.logging** (confirmed on modern
Paper — PaperMC issue #12408: https://github.com/PaperMC/Paper/issues/12408). JUL loggers carry no
handlers and propagate up to the root JUL logger (`Logger.getLogger("")`), where handlers can be
added/removed at will (classic behavior:
https://bukkit.org/threads/using-plugin-getlogger-for-debug-messages.75787/).
Attaching a **session-scoped JUL `Handler` at the root** during the synchronous dispatch window
captures exactly the "plugin logged instead of replying" case:
- ✅ JDK-only — zero dependency, works on every build path.
- ✅ Runnable and assertable headlessly in SmokeTest (create a `Logger`, log, assert captured).
- ⚠️ Does **not** see vanilla/Paper's own log4j-native lines (acceptable: those paths reply via
  the sender, covered by §3.1).
- ⚠️ Attribution by *dispatch window*, not by causality: any plugin's JUL line emitted during the
  window is captured. Mitigations: synchronous window is tiny (one command on the main thread),
  filter out GHBot's own lines (WIBLogger writes JUL with its `[HH:mm:ss]` stamp + our audit
  markers), and cap lines. Flagged in output as `console-log:` lines.

### 3.4 Rejected elsewhere
- **Tailing `logs/latest.log`**: brittle attribution, I/O heavy, duplicates; rejected.
- **jline/terminal interception**: fragile; rejected.

## 4. Proposed design (v0.22.2)

### A1. `CapturingSender` (replaces `ToolBridge.Capture` internals)
New class `dev.ghbot.command.CapturingSender implements CommandSender`:
- legacy: `sendMessage(String)`, `sendMessage(String...)`, UUID variants — capture (as today, plus
  `§`-color strip kept).
- Adventure terminal: `sendMessage(Identity, Component, MessageType)` →
  `PlainTextComponentSerializer.plainText().serialize(component)` → capture.
  (`sendPlainMessage`/`sendRichMessage`/typed defaults funnel into this terminal — no extra code.)
- bungee: `spigot()` returns a `CommandSender.Spigot` subclass capturing
  `sendMessage(BaseComponent…)` (+UUID variants) via `BaseComponent.toPlainText()` — removes the
  current `null` NPE risk.
- identity/permissions unchanged (`hasPermission`/`isOp` true, names as today).
- **bounded**: `MAX_CHARS = 8192` with a `…(+N chars truncated)` marker.
- `ToolBridge.Capture` becomes a thin alias (or its users migrate) so `ToolBridge.run` (the catalog
  tools) gets the same fidelity and NPE-safety — single sender class everywhere.

### A2. `CmdOutput` + `CmdOutputCapture` service (`dev.ghbot.command`)
One orchestration point for all four paths:
1. guardrail check first (sensitive → CONF token, unchanged behavior/audit);
2. open JUL session handler on root logger (thread-safe; nested sessions refcounted);
3. `MainThread.call(() -> Bukkit.dispatchCommand(capturingSender, line))`; on `false` → console
   sender fallback (as today);
4. close handler; merge into `CmdOutput`: status, sender-feed (primary), log-lines (prefixed
   `console-log: `, GHBot self-lines filtered, deduped against sender-feed, ≤30 lines/2 KB);
5. **routing, eyes-style (memory + file + bounded inline):**
   - full merged output → `logs/cmd/<yyyyMMdd-HHmmss>-<firstword>.log` (WIB-timestamped, like
     `logs/eyes/`),
   - inline reply bounded: **web/chat ≤ 4 000 chars / 40 lines** (aligns with `TokenCompress`
     DEFAULT_BUDGET ≈ 1200 tokens; the AI path is additionally compressed as today),
     **in-game ≤ ~1 500 chars / 15 lines** (chat readability); truncation note names the log file.
- audit line in `logs/commands.log` gains `out=<file>` (token-tracked trail stays intact).

### A3. Route all four paths through it
| Path | Change |
|---|---|
| AI tool / AutoTools (`GHBotPlugin:288`) | `dispatchCaptured` delegates to service (signature kept) |
| Web `/cmd` (`WebStatusServer:392`) | per-line service call; reply shows per-line output; keep `;` batches + CONF hint |
| In-game `cmd` (`CommandLearningCommands:30`) | same service, tighter in-game bound |
| `confirm <token>` | confirmed commands run through the same capture (currently console-only) |

### A4. AI/system prompts
CapabilityGuide/AutoTools/help text: document that `cmd` returns real output (bounded), files in
`logs/cmd/`. No new commands in CATALOG (surface unchanged at 27 — shelved-surface smoke stays green).

## 5. Smoke additions (+~12 checks, 420 → ~432)
- `CapturingSender`: Component terminal captured; `sendPlainMessage` default path; legacy string
  path; **interleaved order preserved**; bungee `spigot()` non-null + `BaseComponent` capture;
  truncation at `MAX_CHARS` with marker; `§x§f§f…` hex color stripping.
- `CmdOutputCapture` merge: all four presence/absence combos of {sender-feed, log-lines};
  self-line filter (WIB `[HH:mm:ss]` pattern) drops GHBot's own JUL; dedupe identical lines;
  log-lines cap (30) with truncation note; inline bound + `logs/cmd/` file reference.
- JUL session: synthetic `LogRecord` published during session captured; published outside session
  ignored; nested session refcount; handler always removed (no leak after exception).
- `isSensitive` regression + "no surface change": CATALOG size/shelved-list unchanged.

## 6. Decision log (owner calls needed)

| # | Decision | Proposal | Options |
|---|---|---|---|
| D1 | Scope | **proxy-upgrade + JUL session capture** | proxy-only (smaller) |
| D2 | Full console-tail feed (ring buffer + `/api/console`) | **later, separate release** (JUL-only coverage is partial; full coverage needs the blocked log4j-core dep — §3.2) | include now in JUL-partial form |
| D3 | Inline budgets | web/chat 4 000 chars/40 lines; in-game 1 500/15; full → `logs/cmd/*.log` | adjust |
| D4 | Async settle window (re-read capture ~250 ms after dispatch for late async replies) | **no** — add only if live evidence shows misses | add now |
| D5 | `confirm`-ed commands also captured | **yes** (they're the most important ones to see) | keep console-only |

## 7. Risks & limitations
- **JUL attribution is window-based** (§3.3) — mitigated by sync dispatch + self-filter + caps;
  worst case is a stray unrelated `console-log:` line, never a wrong *execution*.
- A plugin could dispatch commands *reacting* to ours — captured output then includes that output
  too; acceptable (it is causally related).
- Very spammy commands hit bounds — full output always in `logs/cmd/*.log`.
- `PlainTextComponentSerializer` renders translatable components as keys (e.g. LP's translations
  may appear as raw keys in rare cases) — acceptable; legacy-string path unaffected.
- No new CATALOG surface → admin-only posture and shelved-guard untouched.

## 8. Acceptance evidence (owner live test, after smoke green)
1. `cmd lp listgroups` (or any LuckPerms command) → output visible in `/console` reply (Component
   path — this validates the root-cause fix).
2. `cmd plugins` / `cmd version` → vanilla feedback visible (in-game + web).
3. `cmd <something that only logs>` → `console-log:` lines appear; nothing when none.
4. Multi: `cmd lp creategroup A; lp creategroup B` → per-line sections, each with output.
5. `cmd stop` → still ⛔ BLOCKED with CONF token (guardrail regression), `confirm CONF-…` runs.
6. Bounds: long output truncated inline with `logs/cmd/<file>` named; file contains the full text.
7. `logs/commands.log` shows `out=` for audited lines.

---
*Research notes (for the record): dependency-closure checks were run against the resolved Gradle
cache in this workspace (adventure 4.26.1 serializes present; `log4j-core` absent;
`bungeecord-chat` present). GitHub checks: `apache/logging-log4j2` `rel/2.24.1` release has zero
binary assets; ZoneGuard committed Gradle cache contains no `log4j-core`. Adventure no-op terminal
verified against `Audience.java` source (main/4 branch).*
