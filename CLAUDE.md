# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run the application
mvn javafx:run

# Build (compile only)
mvn compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=MessageSerializerTest

# Package (no tests)
mvn package -DskipTests
```

Requires Java 21 and JavaFX 21.

## Architecture

This is a multiplayer co-op platformer called **Threaded** — up to 4 players linked by a physics thread, navigating 3 levels to reach an exit zone. The architecture follows a clean layered structure:

```
domain/         — Pure game entities and rules (no framework deps)
application/    — Use cases and services (game logic, event bus)
infrastructure/ — Network (UDP), audio, serialization
presentation/   — JavaFX controllers and UI components
config/         — GameConfig constants (all tuning values live here)
```

### Networking model (host-authoritative UDP)

- One player acts as **host**; others are **clients**.
- The host runs `HostMatchService.tick(dt)` every frame — physics, collision, scoring all happen here.
- The host broadcasts a full `SNAPSHOT` message to all peers at 24 Hz; clients apply it via `SessionService.updateFromSnapshot()`.
- Clients send `INPUT` messages (targetX, jumpPressed) to the host on every frame and re-send every 50 ms if input is pending.
- UDP messages are plain JSON maps with a `"type"` field. All message types are constants in `MessageSerializer`.

### Game loop (host vs. client)

- `GameController` runs a JavaFX `AnimationTimer` at 60 FPS for both host and client.
- **Host path**: each frame calls `HostMatchService.tick(dt)`, then the snapshot timer fires a broadcast.
- **Client path**: each frame reads incoming UDP packets, applies the snapshot, and does client-side interpolation for rendering.
- `RenderState` objects in `GameController` hold smoothed positions for each player to avoid jitter between snapshots.

### Event bus

`EventBus` is an in-process pub/sub used to decouple game logic from UI. Domain events (`EventNames`) flow from `HostMatchService` → `EventBus` → UI observers (`ScoreBoardObserver`, `EventLogObserver`) and sound (`SoundManager`). The `GameController` also subscribes directly for particle effects and feedback labels.

### Session state

`SessionService` is the single source of truth for all mutable game state: players, platforms, door, button, exit zone, spawn points, timers. Both host and client read from it; only the host writes physics results, while clients write via `updateFromSnapshot()`. Most public methods are `synchronized`.

### Screens / flow

`start_menu.fxml` → (create or join) → `lobby.fxml` → `game.fxml` → `game_over.fxml`. Navigation is done by loading a new FXML into `MainApp.getStage()`. `MainApp.resetRuntimeState()` tears down networking and recreates all services when returning to the menu.

### Level data

Levels are defined procedurally inside `HostMatchService.loadLevel[One/Two/Three]()` — platform positions, button, door, and exit zone coordinates are hardcoded there. `GameConfig` holds all sizing constants referenced by level loaders.
