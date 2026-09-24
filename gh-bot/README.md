# GHBot Plugin — Developer Handoff Brief

**Current version**: v0.28.1 · smoke **646/646 PASS** · **Status**: Production-ready · **jar**: `GHBot-0.28.1.jar`

This document is for AI agents and developers taking over this project. It describes the current state, architecture, and workflow.

## What GHBot Is

GHBot is a PaperMC plugin that turns a Minecraft server into a hands-off AI builder and admin agent. It runs on a **6 GB phone** (Termux/proot, aarch64) with Paper 1.21.11, Geyser/Floodgate, DiscordSRV, Essentials, ViaVersion, and Vault.

**Default bot**: `GH000` · **Web console**: `:8580/console` · **Admin-only**: console + OP players

## The Core Mechanic

**The JSON build spec is the CONTRACT.** Since v0.21.39, pasted JSON specs are parsed and staged directly — the AI never re-interprets them. "100% faithful" means `staged build == spec`.

**Upload button** (v0.21.40+):
- `.json` → staged directly (no pasting 140 KB specs)
- Images → vision provider → JSON spec → staged
- `.txt`/`.cmd`/`.mcfunction` → command script preview (never auto-runs)

## Architecture

```
gh-bot/src/main/java/dev/ghbot/
├── ai/              # AI providers (Gemini, Ollama, OpenAI, fallback)
├── admin/           # Admin operations (config editing, backups, rollback)
├── agent/           # Tool protocol, auto-tools, tool bridge
├── audit/           # Log auditor, update radar, fix rules
├── avatar/          # Enderman avatar (shelved)
├── bot/             # Bot registry, GHBot model
├── builder/         # Build system, design specs, vision verify
├── chat/            # Chat listener, session management
├── command/         # Command registry, learning, script upload
├── config/          # Plugin config
├── core/            # Capability estimator, stats sampler
├── edit/            # Block editing, undo manager
├── location/        # Named locations (shelved)
├── log/             # WIB logger
├── review/          # Ghost service, review commands
├── schematic/       # Schematic codecs (Sponge v2/v3, Classic, Litematica, NBT, mcstructure)
├── session/         # Session persistence
├── terrain/         # Terrain scanner, lattice, coord resolver
└── web/             # Web server, preview registry, auth
```

## Key Design Decisions

1. **No Gson** — JSON is hand-rolled (`ai/JsonUtil.java`) to avoid dependency issues
2. **Hand-rolled NBT** — `schematic/NbtWriter.java` for all schematic formats
3. **Provider-agnostic tools** — `⟦tool:name args⟧` protocol works with any AI provider
4. **Session TTL** — 30 min stale, 100 max sessions, evicted every 5 min
5. **Thread safety** — ConcurrentHashMap for sessions, running builds, undo stacks
6. **Admin-only** — Non-OP players blocked at dispatch (`CommandBridge.isAdminSender`)

## Workflow

### For AI Agents

**Create solid plan, then execute.** Each phase ships as its own release:
1. Build → smoke (+pins) → live Paper check → docs → releases/ swap → GitHub Release
2. Add smoke pins for every fix
3. Run 3 mutations (G/H/I) that kill exactly their pins, then revert
4. Bump version, update CHANGELOG.md, commit, tag, push

**Never claim a fix works without smoke OR live check.** Add smoke pin per fix, mutation-validate, live-validate on sandbox-local Paper when possible.

### Build & Test

```bash
cd gh-bot

# Build
./gradlew jar

# Smoke suite (646 checks)
bash tools/setup-build.sh jar
CP="build/libs/GHBot-*.jar:$(find ~/.gradle/caches -name '*.jar' | grep -v sources | tr '\n' ':')"
javac -proc:none -nowarn -cp "$CP" -d /tmp/smoke-classes tools/SmokeTest.java
java -Xmx384M -cp "/tmp/smoke-classes:$CP" dev.ghbot.SmokeTest

# Doc consistency
bash tools/check-docs.sh 646
```

### Ship

```bash
# 1. Bump version in build.gradle.kts
# 2. Update CHANGELOG.md
# 3. Run check-docs.sh
# 4. Commit + tag
git add -A
git update-index --chmod=+x gh-bot/gradlew gh-bot/tools/*.sh
git commit -m "v0.X.Y — description"
git tag v0.X.Y
git push origin main --tags

# 5. GitHub Release
export GITHUB_TOKEN="..."
bash tools/github-release.sh 0.X.Y "Title" /tmp/notes.md <commit-sha>

# 6. Clean up
shred -u /tmp/notes.md
```

## Sandbox Constraints

- **2 GB RAM** (dev sandbox)
- **Toolchain wiped every turn** — rebuild JDK 21 + Gradle 8.10.2 each session
- **git remote/identity wiped** — re-add each turn
- **`/tmp` wiped** — use for ephemeral files only

## Live Paper Testing

```bash
# Download Paper 1.21.11-132
curl -o paper.jar "https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar"

# Start server
java -Xms256M -Xmx640M -XX:+UseSerialGC -XX:MaxMetaspaceSize=192M \
  -XX:MaxDirectMemorySize=64M -Xss512k -jar paper.jar nogui

# Install GHBot
cp gh-bot/build/libs/GHBot-*.jar plugins/

# Restart and test via web console or rcon
```

## Current Backlog

### Not Solved (optional, ask owner before starting)

- **Vision for real**: Gemini key OR `ollama pull llava` (or qwen2-vl/minimax), then `build.verify-vision: true`
- **Pre-existing `schem` command shadowed by DatasetCommands** — design+export doesn't run; use `export` after stage
- **Sponge v2/v3 both `.schem` overwrite** — pre-existing
- **`/cmd` async race "(no output)"** for bot commands — documented
- **AI-guess audit path** only testable on owner's ollama
- **Deterministic-skeleton stretch** — deferred since v0.25

### Freeze Policy

`BotCommands.SHELVED` includes: avatar, marker, workers, deploy, undeploy, where, save-location, list-locations, delete-locations, teach, dataset, critique, design, image, memory, debuglog, animate, add, editspec, schem download.

Revival = remove from SHELVED + re-add CATALOG + update contract pins. **Do not revive without asking.**

## Documentation

- **[README.md](../README.md)** — User-facing overview
- **[CHANGELOG.md](../CHANGELOG.md)** — Version history
- **[COMMANDS.md](COMMANDS.md)** — Complete command reference
- **[docs/](docs/)** — Deep-dive research and fix write-ups
- **[tools/](tools/)** — Smoke suite, build scripts, release automation

## Key Files

- `GHBotPlugin.java` — Main plugin class, registers all commands
- `ChatService.java` — AI chat sessions, stop button, vision
- `CommandScript.java` — Command-script upload (v0.28.1)
- `WebStatusServer.java` — Embedded HTTP server, auth, upload route
- `CapabilityGuide.java` — AI tool knowledge (injected into system prompt)
- `ToolProtocol.java` — Tool protocol documentation
- `SmokeTest.java` — 646 headless checks

## Contact

- **Owner**: [SG009](https://github.com/SG009)
- **Repo**: https://github.com/SG009/GHBot-Plugin
- **Issues**: https://github.com/SG009/GHBot-Plugin/issues

---

**Last updated**: 2026-09-24 · **Version**: v0.28.1
