# FIX 0.22.4 — jar version stamp lied (`version GHBot` said 0.22.2 on a 0.22.3 jar)

## Symptom (owner evidence, 2026-09-01)

Owner re-ran the batch on the v0.22.3 jar: every fix worked — but chat
`TOOL cmd version GHBot` replied **`GHBot version 0.22.2`** while running the
0.22.3 build. Bukkit prints that string straight from the jar's `plugin.yml`
stamp (`PluginDescriptionFile#getVersion`), so the shipped artifact itself was
stamped wrong.

## Root cause (reproduced in-lab, Gradle 8.10.2)

`plugin.yml` in the jar is stamped by
`processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }`.
**The `expand()` property map is not an up-to-date input of the task.** After a
version bump without `clean`, `processResources` is considered UP-TO-DATE and its
stale output flows into the freshly-named jar. That is exactly the v0.22.3 ship:

```
$ unzip -p releases/GHBot-0.22.3.jar plugin.yml | grep version:
version: '0.22.2'                     ← stale stamp
$ unzip -l releases/GHBot-0.22.3.jar | grep FeedbackForwarder
dev/ghbot/command/FeedbackForwarder.class   ← brand-new v0.22.3 code, same jar
```

Jar **named** GHBot-0.22.3.jar (jar task ran at `version = 0.22.3`) with code
from 0.22.3 but resources stamped at 0.22.2 — the bump commit edited the kts
but nothing invalidated the resource cache.

### In-lab mutation repro (this repo, Gradle 8.10.2)

1. build @ 0.22.4 (no `inputs.property`) → stamp `0.22.4` ✓
2. bump kts → 0.22.5-TEST, `gradle jar`, **no clean** →
   `GHBot-0.22.5-TEST.jar` contains `version: '0.22.4'` — **stale, bug reproduces**
3. add `inputs.property("version", project.version)`, `gradle jar`, still no clean →
   stamp flips to `'0.22.5-TEST'` — **fix proven** (task now re-runs on bump)
4. back to 0.22.4, `gradle clean build` → ship artifact `version: '0.22.4'` ✓

## Fixes

1. `build.gradle.kts` — `processResources { inputs.property("version", project.version); … }`
   forces re-stamping whenever the version changes, clean build or not.
2. Smoke pins (473 checks, +2): `plugin.yml` stamp on the classpath must resolve
   (no raw `${version}` token) and must equal `build.gradle.kts` `version`.
   Mutation-validated: kts bumped without rebuild → suite FAILS naming the drift
   (`0.22.4 vs 0.22.5-TEST`); restored → PASS.
3. `tools/check-docs.sh` — new ship-time guard: if `releases/GHBot-$VER.jar`
   exists, its `plugin.yml` stamp must equal `$VER` ("stale stamp, rebuild").

## Live validation (sandbox-local Paper 1.21.11-132 @c5eb079 = owner's exact build)

- Boot log: `[GHBot] Loading server plugin GHBot v0.22.4` (was `v0.22.2` in prod).
- Web `/cmd?line=version GHBot` → `✓ ran` + `GHBot version 0.22.4` — the exact
  output the owner saw as `0.22.2`.
- Web `/cmd?line=plugins` → `✓ ran` + plugin list — v0.22.3 dispatch fix intact.

Ship: `GHBot-0.22.4.jar` (0.22.3 deleted per policy), smoke **473/473 PASS**.
Owner action: replace the jar in `plugins/` and restart — no config changes.
