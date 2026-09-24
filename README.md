# GHBot-Plugin

**AI-powered builder and admin agent for PaperMC servers** · v0.28.1 · smoke 646/646 PASS · jar `GHBot-0.28.1.jar`

[![Release](https://img.shields.io/github/v/release/SG009/GHBot-Plugin)](https://github.com/SG009/GHBot-Plugin/releases/latest)
[![License](https://img.shields.io/github/license/SG009/GHBot-Plugin)](LICENSE)
[![Paper API](https://img.shields.io/badge/Paper%20API-1.21.11-blue)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)

GHBot is a hands-off AI assistant that manages your Minecraft server and handles in-game builds. It combines natural language understanding with a comprehensive tool surface to replace manual admin work.

## Features

### 🏗️ AI Builder
- **Design & Build**: Describe what you want, get a ghost preview, approve or iterate
- **Vision Import**: Upload images → AI generates build specs → staged for review
- **Command Scripts**: Upload `.txt`/`.cmd`/`.mcfunction` files with server commands, preview before execution
- **Multiple Formats**: Export to Sponge v2/v3, Classic, Litematica, Vanilla NBT, Bedrock `.mcstructure`
- **Undo System**: Drift-guarded undo with `undo confirm` for forced restoration

### 👁️ Terrain Intelligence
- **Lattice Scan**: Per-column heightmap grid for precision placement
- **Look-Then-Set**: AI scans first, calculates offsets, places blocks accurately
- **Gold Exemplars**: Learn from tagged builds for better style consistency
- **Style Sheets**: Hand-editable `styles.yml` for abandoned/medieval/modern/rustic themes

### 🛡️ Admin Tools
- **Audit System**: Monitors server logs, surfaces WARN/ERROR with fix suggestions
- **Update Radar**: Checks Paper + plugins (Essentials, Geyser, Via*, LuckPerms) for updates
- **Web Console**: Secure token-gated interface at `:8580/console`
- **Command Safety**: Systemic commands (`stop`/`reload`/`op`/`whitelist`) require CONF tokens

### 🎨 Review Workflow
- **Ghost Preview**: See builds before applying
- **3D Viewer**: Browser-based voxel renderer with orbit controls
- **Approve/Deny/Redo**: Full iteration cycle
- **Vision Verify**: Optional post-stage image check (requires Gemini/Ollama vision model)

## Installation

1. Download `GHBot-0.28.1.jar` from [Releases](https://github.com/SG009/GHBot-Plugin/releases/latest)
2. Place in your `plugins/` folder
3. Restart the server
4. Access the web console at `http://<server-ip>:8580/console`
5. Use the token printed in server logs to log in

## Quick Start

### In-Game Commands
```
@GH000 scan 20              # Scan terrain around you
@GH000 build a castle       # AI designs and stages a build
@GH000 approve              # Apply the staged build
@GH000 undo                 # Revert last edit
```

### Web Console
Open `/console` in your browser and use natural language:
- "scan 100 at 86 86 262"
- "build a medieval house"
- "audit" (check for errors)
- "status" (server health)

### Command Scripts
Upload a `.txt` file with server commands:
```
# setup_commands.txt
op YOURNAME
lp creategroup vip
lp creategroup admin
```

GHBot previews the commands, flags `YOURNAME` as a placeholder, and waits for you to say:
- `my name is .YourIGN` (fills placeholders)
- `skip step 3` (skips sections)
- `run` (executes through `cmd`)

## Documentation

- **[COMMANDS.md](gh-bot/COMMANDS.md)** — Complete command reference (v0.28.1)
- **[gh-bot/README.md](gh-bot/README.md)** — Developer handoff brief & current state
- **[CHANGELOG.md](CHANGELOG.md)** — Version history and what's new
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — How to contribute
- **[docs/](gh-bot/docs/)** — Deep-dive research and fix write-ups

## Architecture

- **Java 21** on Paper API 1.21.11
- **Hand-rolled JSON** (no Gson dependency)
- **Provider-agnostic AI**: Gemini, Ollama, OpenAI-compatible, rule-based fallback
- **Tool protocol**: `⟦tool:name args⟧` markers for deterministic execution
- **Session management**: TTL eviction, thread-safe ConcurrentHashMap
- **Web server**: Embedded HTTP with SSE streaming, token auth, loopback bypass

## The Core Mechanic

**The JSON build spec is the CONTRACT.** Since v0.21.39, pasted JSON specs are parsed and staged directly — the AI never re-interprets them. "100% faithful" means `staged build == spec`.

Since v0.21.40, the web console has a 📎 upload button:
- `.json` → staged directly (no pasting 140 KB specs)
- Images → vision provider → JSON spec → staged
- `.txt`/`.cmd`/`.mcfunction` → command script preview (never auto-runs)

## Development

### Build
```bash
cd gh-bot
./gradlew jar
```

### Test
```bash
# Smoke suite (646 checks)
cd gh-bot
bash tools/setup-build.sh jar
java -cp "build/libs/GHBot-0.28.1.jar:$(find ~/.gradle/caches -name '*.jar' | tr '\n' ':')" \
  -cp tools/SmokeTest.java dev.ghbot.SmokeTest
```

### Ship
```bash
# Bump version in build.gradle.kts
bash tools/check-docs.sh 646  # verify docs match smoke count
cp build/libs/GHBot-0.28.1.jar ../releases/
git add -A && git commit -m "v0.28.1 — description"
git tag v0.28.1
git push origin main --tags
bash tools/github-release.sh 0.28.1 "Title" /tmp/notes.md <commit-sha>
```

## Requirements

- **Paper 1.21.11** (or compatible 1.21.x)
- **Java 21+**
- **6 GB RAM** (tested on Termux/proot, aarch64)
- **Optional plugins**: DiscordSRV, Essentials, Geyser, ViaVersion, LuckPerms

## License

See [LICENSE](LICENSE) for details.

## Credits

Built and maintained by [SG009](https://github.com/SG009) with assistance from AI agents.

---

**Current version**: v0.28.1 · **Smoke suite**: 646/646 PASS · **Status**: Production-ready
