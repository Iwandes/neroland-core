# Project context for AI coding agents — nerolandcore

> Modrinth is temporarily being removed from the upload and is no longer being referenced. Avoid making references to modrinth.

> This and `CLAUDE.md` are kept identical; update both together.

## The mod

- **Neroland Core** — the foundation **library** for the Neroland sci-fi Minecraft mod ecosystem.
  Core ships APIs, registries, materials, tags, and base implementations — **not gameplay content**.
  Downstream mods (Nerospace, Nerotech, NeroPower, NeroEconomy, NeroFactions, NeroQuests, NeroEvents)
  build on it. Add shared content under `common/` and wire it through each loader entry point.
- Mod id: **`nerolandcore`** (matches the registry namespace + every loader manifest). Package root:
  `za.co.neroland.nerolandcore`. Author: **Neroland**.
- Version: **1.17.3** — the V1 foundation is complete and additive 1.x APIs continue to land. Treat the
  public API as **frozen between majors**; see [`docs/API-STABILITY.md`](docs/API-STABILITY.md).
- Targets **MC 26.1.2, 26.2 AND 26.3** on **NeoForge, MinecraftForge/Forge, and Fabric** → the **"9 cells"**.
  **Java 25.** Mappings = official Mojang names (26.x ships de-obfuscated; no Parchment).

## Core systems (V1 — all shipped)

Six public systems, each a contract downstream mods code against. Reach every one through a small
facade; per-loader wiring is Core's job. Deep developer docs live in [`docs/`](docs/); player- and
pack-maker-facing pages live in [`wiki/`](wiki/Home.md).

- **Materials & tags** — Nero Alloy, Starsteel, Void Crystal, Plasma Glass and their forms, exposed via
  `c:` (interop) and `neroland:` (ecosystem) tags. `registry/` + `data/`; [`docs/TAGS-AND-DATAPACKS.md`](docs/TAGS-AND-DATAPACKS.md).
- **Config** — typed schema registration, validation, hot-reload, server→client sync. `config/`
  (`ConfigManager`/`ConfigSchema`/`ConfigValue`/`CoreConfig`); [`docs/CONFIG.md`](docs/CONFIG.md).
- **Progression gates** — server-authoritative, datapack-overridable milestones. `progression/`
  (`ProgressionGates`/`CoreGates`); [`docs/PROGRESSION.md`](docs/PROGRESSION.md).
- **Currency & reputation** — contracts only; Core stores nothing (NeroEconomy / NeroFactions implement).
  `economy/` + `reputation/`; [`docs/ECONOMY-REPUTATION.md`](docs/ECONOMY-REPUTATION.md).
- **Machine / power / upgrade framework** — Nero energy, `AbstractMachineBlockEntity`, upgrade modules.
  `energy/` + `machine/` + `upgrade/` + `platform/EnergyLookup`; [`docs/MACHINES-POWER-UPGRADES.md`](docs/MACHINES-POWER-UPGRADES.md).
- **Data & compliance** — shared per-player erasure, retention, opt-out. `data/`
  (`PlayerDataErasure`); [`docs/COMPLIANCE.md`](docs/COMPLIANCE.md).

Additive 1.x additions on top of those six: decor contracts (1.9.0 — `palette/`,
`link/display/`, `decor/DecorTags`; [`docs/DECOR-CONTRACTS.md`](docs/DECOR-CONTRACTS.md)) and,
in 1.10.0, the **space tags** `neroland:space/*` (`worldgen/SpaceTags` — biome +
dimension-type vocabulary for off-Earth places, membership shipped `"required": false` so the
tags are legitimately empty without a planet mod) and the **entity registration seam**
(`entity/EntityRegistrationSupport` — default attributes + spawn placements declared from
common code, flushed by per-loader plumbing); both in
[`docs/SPACE-TAGS-AND-ENTITIES.md`](docs/SPACE-TAGS-AND-ENTITIES.md).

Cross-cutting: ServiceLoader platform seams (`platform/`, `network/`), a unified `event/CoreEvents`
bus facade, and Sentry telemetry (`telemetry/`, opt-out via `telemetryEnabled`). Commands live under
`/neroland …` (`command/CoreCommands`).

## Working rules

- **Keep responses concise and direct** — minimal verbosity, minimal formatting.
- **POPIA & GDPR**: keep all logging/telemetry/scripts compliant — only public version strings, never
  personal data; minimise data, set retention limits, support export/erasure and opt-out.
- **NEVER commit or push automatically.** Leave changes **staged**; the developer reviews and commits
  with native git (the source of truth).
- **Use relative paths only** — never hard-code machine-specific absolute paths in committed files.
- **Never run commands against production databases.** Treat any DB command as illustrative.

## Repo layout — flattened cross-loader build

- **The build IS the repo root.** `common/` (shared source spliced into every node), `neoforge/`
  (ModDevGradle), `forge/` (ForgeGradle), `fabric/` (Fabric Loom). Root build files: `settings.gradle`,
  `stonecutter.gradle` (the REAL root build script — Stonecutter repoints `buildFileName` here; the root
  `build.gradle` is inert), `gradle.properties`, `gradlew`, `gradle/`.
- **Version/loader axis = Stonecutter.** Each loader×MC is a real node `:<loader>:<mc>`
  (`:fabric:26.1.2 :fabric:26.2 :fabric:26.3 :neoforge:26.1.2 :neoforge:26.2 :neoforge:26.3 :forge:26.1.2 :forge:26.2 :forge:26.3`). `common` is
  NOT a node — its source is spliced via `rootProject.ext.commonJava` / `commonResources`. Dependency pins
  live in `gradle.properties` as `*_version_<mc>` keys; `mc_versions=26.1.2,26.2,26.3`.
- **Version-specific code in `common/`.** Non-active nodes run `common/` through Stonecutter (`stonecutterProcessCommon`), so shared code uses the same `//? if >=26.3 {` blocks as the loader `src/` trees. Keep the files in the vcsVersion state, and never put a `*/` inside a disabled block. Datapack files whose format differs by version go in `common/src/main/resources-<mc>/`, which is merged over `common/src/main/resources` for every node at or above `<mc>` (`mergeCommonResources`).

## Build & verify

- Build the cells with the Gradle wrapper, e.g. `./gradlew :fabric:26.2:build` or all nine:
  `:neoforge:26.1.2:build :neoforge:26.2:build :neoforge:26.3:build :forge:26.1.2:build :forge:26.2:build :forge:26.3:build
  :fabric:26.1.2:build :fabric:26.2:build :fabric:26.3:build`.
- Static analysis: `./gradlew :fabric:26.2:ecjCheck` (the VS Code Problems panel, via `tools/ecj.prefs`).
  The task only FAILS on errors.
- A Cowork agent sandbox cannot decompile Minecraft — run builds natively (or via the local gradle MCP)
  on the developer's machine.
- **Verify the cells build before marking a task done.** Never sign off on an uncompiled change.

## Conventions (cross-loader)

- **Resources are HAND-AUTHORED in `common/src/main/resources`** — the multiloader does not run datagen.
  Validate JSON after edits.
- **Platform seams via ServiceLoader (no Architectury).** Put loader-agnostic code in `common/`; ship one
  impl per loader plus a `META-INF/services` entry. Keep `common/` free of `net.neoforged.*` /
  `net.fabricmc.*` / `net.minecraftforge.*` imports.
- Loader entry points: `NerolandCoreFabric` (+ `NerolandCoreFabricClient`), `NerolandCoreForge`,
  `NerolandCoreNeoForge` — each calls `NerolandCoreCommon.init()` during construction.
- NeoForge/Forge debug tasks use `-PnerolandcoreDebug`; Fabric Loom honours Gradle `--debug-jvm`.

## IDE (VS Code) run & debug

- Workspace: **`nerolandcore.code-workspace`** (single-root `"."`). Import the Stonecutter nodes as **static
  Eclipse projects**: `./gradlew eclipse` (live Buildship/Loom import is disabled —
  `java.import.gradle.enabled=false`). Re-run `./gradlew eclipse` after dependency changes, then reload
  VS Code. Per-node Eclipse project names are `nerolandcore-<loader>-<mc>`.
- **Run/Debug** a cell from `tasks.json` / `launch.json`.

## Wiki — keep `wiki/` updated

- This mod has its own **dedicated wiki** in `wiki/` at the repo root: the player- and
  contributor-facing docs for Neroland Core (features, blocks/items, machines, progression, recipes, FAQ).
- **Whenever you add, change, or remove a feature, update `wiki/` in the same change** — treat the
  wiki as part of "done"; code without a matching wiki update is incomplete.
- One page per topic; keep `wiki/Home.md` as the index that links every page, with relative links
  between pages. Validate Markdown via the gradle MCP `markdown_check` (honours `.markdownlint.json`).
- The wiki is **per-mod** — document only Neroland Core here; cross-mod / ecosystem concepts belong in the
  relevant other mod's own wiki.

## DO NOT

- Commit or push automatically — leave changes staged for the developer.
- Hard-code absolute machine paths in committed files.
- Add loader-specific code to `common/` — use the platform seams.

## Translation workflow

English is the source language for new and changed text. Community translations are intended to be contributed through Weblate; other-language translations are not required when implementing a feature. Preserve existing translations. This supersedes older instructions that require translating every change.
