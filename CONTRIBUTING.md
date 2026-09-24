# Contributing to GHBot-Plugin

Thanks for your interest in contributing! This document provides guidelines and information for contributors.

## Code of Conduct

- Be respectful and constructive
- Focus on the technical merits of contributions
- Assume good intentions from other contributors

## How to Contribute

### Reporting Bugs

1. Check [existing issues](https://github.com/SG009/GHBot-Plugin/issues) first
2. Use the bug report template
3. Include:
   - Server version (Paper build number)
   - GHBot version (`version GHBot`)
   - Relevant logs (`logs/ghbot.log`, `logs/latest.log`)
   - Steps to reproduce
   - Expected vs actual behavior

### Suggesting Features

1. Open a discussion or issue with the feature request template
2. Describe the use case and problem you're solving
3. Consider the 6 GB phone constraint (RAM, CPU, storage)
4. Propose how it fits into the 4-pillar architecture

### Submitting Pull Requests

1. Fork the repository
2. Create a feature branch (`feature/your-feature`)
3. Make your changes
4. Add smoke tests (see Testing below)
5. Update documentation
6. Run `bash gh-bot/tools/check-docs.sh <smoke-count>`
7. Submit a PR with a clear description

## Development Setup

### Prerequisites

- Java 21 (Adoptium/Temurin recommended)
- Gradle 8.10.2 (or use the wrapper)
- Git

### Building

```bash
cd gh-bot
./gradlew jar
```

The jar will be in `build/libs/GHBot-<version>.jar`.

### Testing

#### Smoke Suite

The smoke suite (`gh-bot/tools/SmokeTest.java`) runs 642 headless checks covering:
- Core mechanics (scan, build, edit, undo)
- AI tool protocol
- Web server auth
- Command parsing
- Schematic codecs
- Vision verify logic

Run it:
```bash
cd gh-bot
bash tools/setup-build.sh jar
# Compile and run smoke
javac -proc:none -nowarn -cp "build/libs/GHBot-*.jar:$(find ~/.gradle/caches -name '*.jar' | tr '\n' ':')" \
  -d /tmp/smoke-classes tools/SmokeTest.java
java -cp "/tmp/smoke-classes:build/libs/GHBot-*.jar:$(find ~/.gradle/caches -name '*.jar' | tr '\n' ':')" \
  dev.ghbot.SmokeTest
```

#### Live Testing

For runtime-visible changes, boot a local Paper server:
```bash
# Download Paper 1.21.11
curl -o paper.jar "https://fill-data.papermc.io/v1/objects/..."

# Start server
java -Xms256M -Xmx640M -XX:+UseSerialGC -jar paper.jar nogui

# Install GHBot
cp gh-bot/build/libs/GHBot-*.jar plugins/

# Restart and test
```

### Mutation Testing

Every fix must include smoke pins that fail when the fix is reverted:

1. Add pins that verify the fix works
2. Temporarily revert the fix (mutation)
3. Confirm the pins fail (they "kill" the mutation)
4. Restore the fix
5. Document the mutation in the commit message

This prevents regressions and ensures fixes are actually tested.

## Code Style

- **Java 21** features (records, pattern matching, text blocks) are encouraged
- **Package structure**: `dev.ghbot.<module>` (never `dev.ghbot.build`)
- **JSON**: Hand-rolled via `ai/JsonUtil.java` (no Gson dependency)
- **Naming**: camelCase for methods, PascalCase for classes, UPPER_SNAKE for constants
- **Comments**: Explain *why*, not *what* (the code shows what)
- **Logs**: Use `WIBLogger` (WIB timezone, structured format)

## Documentation

- **README.md**: User-facing overview and quick start
- **gh-bot/README.md**: Developer handoff brief (current state, backlog)
- **COMMANDS.md**: Complete command reference
- **CHANGELOG.md**: Version history (what changed, not how)
- **docs/**: Deep-dive research and fix write-ups

When adding features:
1. Add to `BotCommands.CATALOG`
2. Update `COMMANDS.md`
3. Update `CapabilityGuide.java` (AI tool knowledge)
4. Update `ToolProtocol.helpText()` (tool protocol docs)
5. Update smoke suite to verify the feature is wired

## Shipping Checklist

Before releasing a new version:

1. [ ] Bump version in `gh-bot/build.gradle.kts`
2. [ ] Update smoke suite (add pins for new features)
3. [ ] Run `bash gh-bot/tools/check-docs.sh <smoke-count>`
4. [ ] Update `CHANGELOG.md`
5. [ ] Update `README.md` and `gh-bot/README.md`
6. [ ] Commit: `v0.X.Y — short description`
7. [ ] Tag: `git tag v0.X.Y`
8. [ ] Push: `git push origin main --tags`
9. [ ] Create GitHub Release with `gh-bot/tools/github-release.sh`
10. [ ] Delete old jar from `releases/` (policy: only newest exists)

## Architecture Notes

- **Main thread**: Block placement, world reads (via `MainThread.call()`)
- **Async threads**: AI HTTP calls, web server, chat processing
- **Session keys**: `botId|sessionName` (e.g., `GH000|web`)
- **Tool protocol**: `⟦tool:name args⟧` or `[tool:name args]` markers
- **CONF tokens**: Systemic commands mint `CONF-<timestamp>-<random>`, require `confirm <token>`

## Questions?

- Open a [discussion](https://github.com/SG009/GHBot-Plugin/discussions)
- Check [existing issues](https://github.com/SG009/GHBot-Plugin/issues)
- Read the [docs/](gh-bot/docs/) directory for deep-dives

Thank you for contributing! 🎉
