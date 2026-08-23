# Celeste Client

Private Fabric client for Minecraft 1.21.11 (`mod id`: `celeste`, archive name `celeste-client`). Java 21, Fabric Loom, Yarn mappings. Default branch is `main`. Repo: `c4lamity007/celeste-updated`.

This README is for agents and humans who need to navigate the tree without guessing. Do not invent packages. Start from `BuiltInFeatures` and the class paths below.

## Stack

| Piece | Value |
| --- | --- |
| Minecraft | 1.21.11 |
| Yarn | `1.21.11+build.6` |
| Fabric Loader | 0.19.3 |
| Fabric API | `0.139.5+1.21.11` |
| Loom | 1.17.17 |
| Java | 21 (`options.release = 21`) |
| Maven group | `dev.c4lam` |
| Entrypoint | `dev.c4lam.celeste.CelesteClient` |
| Mixins | `src/main/resources/celeste.mixins.json` (`dev.c4lam.celeste.mixin`, JAVA_21, priority 2000) |
| License | All Rights Reserved |

Versions live in `gradle.properties`. Root project name in `settings.gradle` is `celeste-client`.

## Build and run

Need JDK 21. From the repo root:

```bash
./gradlew build
./gradlew runClient
```

On Windows use `gradlew.bat`. Loom run configs add `--enable-native-access=ALL-UNNAMED` and `-Djoml.nounsafe=true`.

In game, Right Shift opens `CelesteScreen` (or closes it / the HUD editor if already open).

### Native DLL gotcha

`build.gradle` `processResources` currently copies a JNI bridge from a **machine-local path**:

`C:/Users/c4lam/Downloads/New folder (5)/MediaPlayerInfo.dll`

into `mediaplayerinfo/natives/win` inside the jar. Builds on any other machine fail unless that file exists or the copy is replaced.

There is an open PR to vendor it: [#1 Vendor MediaPlayerInfo.dll](https://github.com/c4lamity007/celeste-updated/pull/1) on branch `vendor-mediaplayerinfo-dll`. Also see `src/main/resources/mediaplayerinfo/` and `src/main/native/`. Media code is `dev.c4lam.celeste.media.SystemMediaService`.

## Boot order

`CelesteClient.onInitializeClient()`:

1. `GuiPipelines.register()`
2. `InterfaceConfig.initialize()`
3. `FriendManager.getInstance().initialize()`
4. `HudManager` + `SystemMediaService` + `GameplayFoundation`
5. `ModuleManager` + `BuiltInFeatures.register(modules)` + `modules.initialize()`
6. `ConfigProfileManager.getInstance().initialize()`
7. HUD sync with module-backed elements
8. Right-Shift keybind

On stop: modules, interface config, gameplay foundation, friends, media, render cleanup.

## Directory map

```
build.gradle / settings.gradle / gradle.properties
src/main/java/dev/c4lam/celeste/
  CelesteClient.java          # Fabric client entry
  action/                     # GameplayFoundation and action helpers
  config/                     # Profiles + interface config + image codec
  friend/                     # FriendManager (in-memory + persist; see Friends below)
  gui/                        # CelesteScreen click GUI
  hud/                        # HudManager + HudEditorScreen
  license/
  media/                      # System media / JNI helper
  mixin/                      # Mixins listed in celeste.mixins.json
  module/                     # Module system + BuiltInFeatures registry
    impl/combat|movement|visual|expansion
    expansion/inventory|sword
  render/                     # Pipelines, fonts, RenderUtil
  rotation/
  silent/
src/main/java/dev/redstones/  # Third-party / helper package (inspect before editing)
src/main/resources/
  fabric.mod.json
  celeste.mixins.json
  assets/
  META-INF/
  mediaplayerinfo/
src/main/native/
net/minecraft/                # Leftover tree at repo root. Not the Fabric source set. Do not treat as the live codebase.
```

## Modules

One explicit registry: `src/main/java/dev/c4lam/celeste/module/BuiltInFeatures.java`.

Core types:

- `ModuleFeature` — feature contract / settings
- `ModuleManager` — runtime registry, init, close
- `ModuleContext` — per-tick / world context

Categories on disk:

| Area | Path |
| --- | --- |
| Combat | `module/impl/combat/` (auras, crystals, anchors, surround, trap, mace, TBOT, …) |
| Movement | `module/impl/movement/` (sprint, safewalk, elytra, boat flight, fake fly) |
| Visual core | `module/impl/visual/` (`EntityEspFeature`, `HoleEspFeature`, `VisualWorldRenderer`, fullbright) |
| Visual extras | `module/impl/visual/expansion/` (Arrow ESP, item ESP, target ESP, ore ESP, trajectories, HUD widgets) |
| Misc extras | `module/impl/expansion/` (auto tool/mlg/firework, keep sprint, trap save, web breaker, see rotations) |
| Inventory | `module/expansion/inventory/` (Auto Refill, pots, totems, silent inv, fast place/exp, …) |
| Sword extras | `module/expansion/sword/` |

Registration extras: `ExpansionFeatures.register`, `VisualExpansion.register`, `InventoryExpansion.register`, `SwordExpansion.register`.

World overlays hook through `VisualWorldRenderer` (entity/hole ESP) and `VisualExpansionRenderer` (arrow / item / target / ore / trajectories).

### Arrow ESP (upcoming)

- Feature: `src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/ArrowEspFeature.java`
- Draw path: `VisualExpansionRenderer.java` (same package)
- Registered from `VisualExpansion`

Known issues to fix next (do not implement in a README-only change):

- Arrows draw for on-screen players too (clutter on the crosshair). Off-screen only.
- Styles 2 and 3 use a 1×1 quad, so they render as a speck. Fix those sizes, then remove style/mode 1.
- Arrow color is three 0–255 sliders. Add a real color picker. Other ESP currently has no color UI; keep that consistent unless a picker is added across ESP.

### Auto Refill → Potion Refill (upcoming)

- Feature: `src/main/java/dev/c4lam/celeste/module/expansion/inventory/AutoRefillFeature.java`
- Inventory helpers: `InventorySupport`, `InventoryExpansion`, mixins `ClientPlayerInteractionManagerSlotMixin`, `PlayerInventorySlotMixin`, `expansion.inventory.*`
- Hover-click cousins: `HoverTotemFeature`, `HeldUseRepeater`, `AutoPotFeature`, `ThrowPotFeature`

Planned behavior:

1. Rename the module to **Potion Refill**.
2. **Hover** mode: when the cursor is over a potion, click immediately if delay is 0ms; otherwise use randomized CPS. Alternate left and right click. Slot range is selectable (min/max). Optional dedicated strength/speed slots.
3. **Automatic** mode: send slot-swap packets without requiring the mouse over the potion. Reference implementation lives outside this repo at `C:\Users\c4lam\OneDrive\Documents\Villians Utility Mod - REAL\src` (Villians Utility Mod). Mirror that packet/slot logic; do not guess.

### Friends (upcoming)

- Manager: `src/main/java/dev/c4lam/celeste/friend/FriendManager.java`
- GUI: `src/main/java/dev/c4lam/celeste/gui/CelesteScreen.java` and `gui/model/`
- Config neighbors: `config/ConfigProfileManager.java`, `InterfaceConfig.java`, `ConfigImageCodec.java`

Planned behavior:

- Right-click in the friends panel to type-add by Minecraft username.
- X on the corner of a friend row to remove (not only middle-click).
- Each user gets a **separate friends cloud config**. It must not ride normal cloud/profile configs. Not shareable. Not exportable.

## Config

- `InterfaceConfig` — UI / client interface state, initialized before modules.
- `ConfigProfileManager` — named profiles (the normal shareable/exportable cloud configs). Keep friends **out** of this path.
- `ConfigImageCodec` — image encode/decode for config visuals.

When you add settings, keep existing config keys stable unless the task is an explicit rename (Potion Refill is one).

## Mixins

Declared in `src/main/resources/celeste.mixins.json`. Package `dev.c4lam.celeste.mixin`. Client-only. Priority 2000.

Groups you will touch often:

- Input / slots: `ClientPlayerInteractionManagerSlotMixin`, `PlayerInventorySlotMixin`, `MinecraftClientAttackInvoker`
- Inventory expansion: `expansion.inventory.InventoryExpansionHandledScreenAccessor`, `expansion.inventory.MinecraftClientUseInvoker`
- Visual: `GameRendererMixin`, `InGameHudMixin`, `LivingEntityRendererMixin`, `module.FullbrightLightmapMixin`, `module.VisualSwingDurationMixin`
- Flight / movement: `BoatFlight*`, `FakeFly*`, `EntityMoveFixMixin`, `KeyboardInputMoveFixMixin`

Add new mixins to both the Java tree and `celeste.mixins.json`.

## Other branches

- `main` — default working branch
- `docs/agent-readme` — this README
- `vendor-mediaplayerinfo-dll` — PR #1, vendoring the JNI DLL
- `celeste-configs-and-copy` — config-related work; check before overlapping profile/cloud code

## Before you edit (agent checklist)

1. Confirm the feature is registered in `BuiltInFeatures` or one of the `*Expansion.register` helpers.
2. Open the feature class first, then its renderer/packet helper, then any mixin.
3. For inventory/packet work, read `InventorySupport` and the slot mixins before inventing a new swap path. For automatic potion refill, also read the Villians Utility Mod sources on disk.
4. Keep module display names, setting keys, and cloud schema compatible unless the task is a rename.
5. Do not commit `run/`, `.gradle/`, or local DLL paths. Do not treat `net/` at the repo root as live sources.
6. Client-only. No server entrypoint.
7. Prefer small PRs: one feature area per branch.

## Conventions observed

- Features are `*Feature` classes, registered once in `BuiltInFeatures` (guarded by a `registered` flag).
- Visual world draw is centralized (`VisualWorldRenderer`, `VisualExpansionRenderer`) rather than each module issuing raw world draws.
- Combat overlay has its own renderer (`CombatOverlayRenderer`).
- GUI names should stay auditable from the registry (comment on `BuiltInFeatures`).
- Mixins use accessors/invokers instead of extra reflection where possible.
