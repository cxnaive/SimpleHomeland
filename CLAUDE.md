# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

- **Build plugin JAR:** `./gradlew shadowJar` → outputs to `build/libs/SimpleHomeland-{version}.jar`
- **Clean build:** `./gradlew clean shadowJar`
- **No tests exist** in this project — there is no test source set.

## Project Overview

SimpleHomeland is a Paper/Folia Minecraft server plugin (Java 21) that gives each player independent world instances ("homelands"). Written in Chinese (messages, config, comments). Licensed MIT.

**Hard dependencies:** PlaceholderAPI, Worlds (net.thenextlvl:worlds)
**Soft dependencies:** XConomy (economy), PlayerPoints (points currency)
**Runtime libraries** (loaded by server via `plugin.yml libraries:`, not bundled): HikariCP, H2, MySQL Connector, Gson

## Architecture

Manager-based singleton pattern. All managers are held by `SimpleHomelandPlugin` and accessed via `plugin.getXxxManager()`.

**Startup order:** ConfigManager → DatabaseManager → DatabaseQueue → EconomyManager → PlayerPointsManager → WorldsProvider (from services) → HomelandManager → Listeners → Command → PAPI Expansion

**Key data flow:**
- Player data cached in `HomelandManager` via `ConcurrentHashMap<UUID, List<Homeland>>` + `worldKeyIndex` (key→Homeland) + `worldUuidIndex` (UUID→Homeland)
- All DB writes go through `DatabaseQueue.submit()` — single-threaded async queue (BlockingQueue, capacity 1000). Callbacks run on the DB thread; use Folia-safe scheduling to return to game threads.
- `Homeland` model uses `AtomicBoolean`/`AtomicInteger` fields for thread-safe reads across cache and DB threads.

**Core packages:**
- `command/` — `/homeland` (alias `/hl`) command executor + tab completer; delegates all logic to `HomelandManager`
- `config/` — Loads config.yml, messages.yml, rules.yml, gui.yml. `GUIConfig` parses slot layouts. `PriceTier`/`ExpansionPriceTier` model tiered costs.
- `database/` — HikariCP pool (H2 or MySQL) + single-threaded async `DatabaseQueue`
- `economy/` — Optional XConomy and PlayerPoints integrations
- `generator/` — `SkyblockChunkGenerator` for void-world generation (5×5 starting island)
- `gui/` — All extend `AbstractGUI` (pagination, border, click actions). `GUIManager` is a static registry of open GUIs. `GUIListener` handles inventory events.
- `listener/` — Six Bukkit event listeners: player join/quit/respawn/chat, visitor flag enforcement, teleport access checks, world load restore, command blocking
- `manager/` — `HomelandManager` (~900+ lines): CRUD, teleport, economy payments, invites, auto-unload, world management. The central business logic class.
- `model/` — `Homeland` (data model), `VisitorFlag` (17 permission flags enum), `VisitorFlags` (EnumMap with JSON serialization)
- `placeholder/` — PlaceholderAPI expansion
- `api/` — `SimpleHomelandAPI` static facade for other plugins; delegates to `HomelandManager.teleportXxxAPI()`

## Key Conventions

- Version is defined in `build.gradle.kts` and injected into `plugin.yml` via `processResources` template expansion.
- All user-facing messages are in `messages.yml` (Chinese), accessed through `MessageUtil`.
- GUI layouts are fully configurable in `gui.yml` (slot positions, materials).
- GameRules exposed to players are defined in `rules.yml` with per-rule materials and defaults.
- Folia compatibility: use region/entity schedulers instead of `Bukkit.getScheduler()`.
- Config files use `saveDefaultConfig()` + custom YamlConfigurations; reload via `configManager.load()`.
