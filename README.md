# Village Expansion 1.4.14


## Version 1.4.14 — material dependency recovery and planning responsiveness

Version 1.4.14 corrects the order in which deferred crafting dependencies resume. If a wooden shovel needs planks and the plank recipe needs logs, the log dependency is completed first, then the plank output is made, and only then is the shovel retried. Existing saved tasks are migrated from the old root-first queue order automatically.

For vanilla outputs, a viable vanilla recipe now wins over modded alternatives. A modded recipe remains usable when no executable vanilla recipe exists. Material planning also receives an independent eight-slice round-robin budget per tick; each worker can advance at most two phases in a tick, and recipe registry indexing is shared rather than repeated by each worker. Unavailable-plan retries default to 40 ticks. The config migration advances `performanceTuningVersion` to 10 once and updates the planning-related timing keys; subsequent manual edits are preserved.

New configuration keys: `lazyPlanningSlicesPerTick` (1–32, default 8) and `materialPlanFailureRetryTicks` (10–600, default 40). `lazyPlanningRecipeEntriesPerTick` now defaults to 256.

Fabric mod that lets unemployed villagers start settlements, prepare land, gather materials, dig mines and build village-style homes. The project currently targets **Minecraft 1.21.1 only**.

## 1.4.11 test bootstrap fix

- Initialized Minecraft's game version and vanilla registries in `MaterialToolCompatibilityTest` before tests access static `Items` constants. The previous six failures all originated from the first registry-backed test invocation; the remaining `NoClassDefFoundError` failures were follow-on errors after the initial class initialization failed.
- GitHub Actions now uploads the JUnit HTML and XML reports whenever the test/build job fails, making the full exception chain available for the next diagnosis.
- No gameplay logic changed.

## 1.4.10 build fix

- Added the missing `net.minecraft.block.Block` import in `MaterialAcquisitionPlanner`, resolving the reported `cannot find symbol: class Block` compiler error. No gameplay logic changed.
- The previous 1.4.8 behavior and acceptance scenarios remain unchanged; see `docs/TEST_SCENARIOS_1.4.8.md`.

## 1.4.8 changes

- Tree mining tolerates leaf blocks in the working ray and tries another side of the same log after a failed navigation route or knockback. Progress is retained while the worker returns to reach.
- Plains/Oak/regular Forest house materials normalize to oak, including saved in-flight spruce goals. Nearby trees are checked and ranked before distant candidates.
- Tool substitution follows harvest capability: stone satisfies wooden shovel, gold remains wooden harvest tier, iron is required for diamond ore, and diamond is required for obsidian.
- Nearby crafting tables/furnaces are recognized. A temporary crafting table remains placed during its 20-tick use window and is recovered after the recipe. A temporary furnace remains lit throughout the mod's timed/simulated smelting operation and is recovered after.
- Builder profession overlay uses a slate-blue hardhat and muted teal/leather workwear while retaining the original transparent UV mask.
- Server acceptance matrix: `docs/TEST_SCENARIOS_1.4.8.md`.

## 1.4.8 follow-up refinements

- Missing professional-supplier checks now continue to ordinary acquisition planning on the next tick instead of waiting another lazy-planning interval.
- Workstation discovery context is rechecked every 10 ticks while a completed material plan is waiting, so a table/furnace found nearby invalidates a stale plan faster.

## 1.4.7 changes

- Saved in-flight `spruce_log` tree goals are migrated to the house site's oak family for Plains tasks, even when the saved biome marker already says `plains`.
- Tool capability checks include the offhand and preserve the replaced tool. Nearby workstations are searched within six horizontal and three vertical blocks, and a newly reached workstation refreshes the plan.
- Temporary crafting tables and lit furnaces can be placed safely and reclaimed after use. Nearby permanent stations are never removed.
- Tree lookup prioritizes the nearest accessible local tree and retries alternate stands around obstructions while preserving mining progress.

## 1.4.6 changes

- Wood requests are checked against the final house plot biome on every task update (with an early return when unchanged); changing the plot biome remaps its blueprint and pending/deferred wood goals. For a Plains house this means oak-family wood even if the builder currently stands in a different biome. The task label includes the house biome, and the server log records house biome, worker biome and selected family.
- Mining progress is kept for the same block and coordinate even when only block-state properties change. Knockback temporarily pauses mining outside a four-block horizontal radius; accumulated progress remains and resumes when the villager returns.
- If a work stand becomes unreachable, the builder tries another side of the same log instead of advancing to the next log and resetting progress. Stand search now examines four rings and avoids occupied feet/head cells.
- Natural trees are ranked by the distance to their accessible work stand, improving nearest-tree selection where hills or foliage make the trunk coordinate a misleading distance measure.
- Replaced the bright yellow/gold builder outfit with a slate-blue hardhat, muted teal workwear and brown utility belt. Both villager and zombie-villager profession overlays use the same new 64x64 texture.

## 1.4.5 changes

- Exact raw-log requests skip recipe/provider planning and immediately search for the matching natural tree; harvesting does not require an axe.
- Natural-tree lookup now advances through every block column in a center-out spiral, with bounded 1,024-column slices and no repeated sampling of the same positions.
- Wood search retries more frequently, reports whether the builder is searching or walking to a tree, and preserves the blueprint's biome-native wood requirement. The template palette is reloaded after plot selection, so it uses the actual house site's biome rather than the village center.
- Existing saved tasks migrate wooden material goals to the family used by the reloaded, biome-adapted blueprint (for example, stale spruce requests become oak requests in Oak Forest).
- The direct raw-log path resets its retry timer only when a new log goal begins; failed scans no longer cause a full search attempt every task tick.
- Adds spiral-search unit tests and eight additional in-game acceptance cases for off-grid trees, Oak Forest logs, saved-task migration and retry cooldowns.

## 1.4.4 changes

- Fixed a circular dependency that stopped exact oak/spruce log requests before movement: a builder can now harvest natural logs by hand when it has no axe, then craft a tool from the collected wood.
- Tool selection no longer deletes an unrelated item from the main hand when no tool of the requested family is available.
- Replaced sparse random tree searches with a deterministic, retry-shifted grid. It finds the exact required species, checks more natural forest-floor blocks and gradually expands the search radius.
- Converted wooden blocks in vanilla house templates to the local biome's wood family. Oak/regular forest uses oak; birch forest uses birch; dark forest uses dark oak; taiga/snow uses spruce; jungle, savanna, mangrove, cherry and pale garden use their corresponding wood families.
- Preserved block-state properties during conversion and added regression tests for biome selection and block-path mapping.

## 1.4.3 changes

- Fixed a navigation arbitration regression that could leave builders planning work but not moving. The controller no longer stops Navigation whenever its transient `moveTarget` is null during a planning/stage transition.
- Throttled Brain-vs-builder navigation arbitration to one check every 20 ticks. Reissuing `startMovingTo()` every entity tick could rebuild a valid path before it advanced, appearing as a frozen villager.
- Kept profession enforcement and active mining progress on every entity tick, independently from the slower navigation arbitration.
- Added pure JUnit regression tests for navigation arbitration timing and route reassertion.
- Added a new in-game acceptance scenario for movement during planner transitions and confirmed that an active route is not restarted every tick.

## 1.4.2 changes

- Block-breaking duration is calculated from the current block hardness, the held tool's mining-speed multiplier and vanilla's suitable/unsuitable harvest divisor. The legacy `blockBreakBaseTicks` config key is retained for compatibility but no longer controls mining speed.
- Active block-break progress advances every game tick independently of the round-robin construction planner, so a large builder count does not multiply the time needed to break a block.
- Broken tools trigger the existing material planner before the worker resumes tree/stone mining; a better tool already in inventory is equipped first.
- Failed resource/navigation targets are temporarily rejected. Tree logs, leveling stands, mine entrances and construction stands have bounded retries/fallbacks.
- A blueprint cell occupied by a living entity is retried for at most 600 ticks; after that, only that blocked cell is skipped. Other cells continue.
- While a construction task is active, its villager is forced to the temporary Builder profession and vanilla `JOB_SITE` / `POTENTIAL_JOB_SITE` memories are cleared after each Brain tick. Vanilla safety and sleep behavior remain available.
- JUnit regression tests and versioned in-game acceptance matrices are included under `src/test` and `docs/TEST_SCENARIOS_*.md`.


## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16.14 or a compatible later loader for 1.21.1
- Fabric API for Minecraft 1.21.1
- Java 21

This is a source project. It is not a precompiled JAR. The included GitHub Actions workflow builds the mod and uploads the JAR as an artifact.

## Main behavior

### Construction priority, danger and sleep

- The vanilla Villager Brain continues ticking. After Brain updates, the mod arbitrates construction navigation at a 20-tick interval. It only rebuilds the route when navigation is idle, its destination was replaced, or the configured reassertion interval elapsed; transient planner states with no custom destination do not cancel a valid navigation path.
- If a hostile mob enters the configured danger radius or the villager is attacked, construction navigation priority is temporarily yielded so vanilla flee behavior can run. The same pause is applied while the villager is burning, drowning or at critically low health (25% or less), giving vanilla movement/safety goals control before construction resumes from its saved stage.
- A navigation watchdog detects lack of movement progress, retries alternate nearby standing positions, and logs repeated route failures instead of silently waiting forever.
- If a material search repeatedly fails, the worker expands its search radius (up to 96 blocks), logs the exact requested item and retries without discarding the active house project. Stone-like crafting ingredients and supported raw ores can use the underground mining route when no exposed target is found. If surface dirt is unavailable for a leveling fill, the worker can obtain stone through the mine and use it as a substitute.
- At night, a builder with an assigned `HOME` memory is allowed to use its vanilla schedule to reach and sleep in its bed. The founding villager keeps the first house bed while it returns for the starter profession house. Its construction stage resumes after morning.
- Only adult villagers with profession `none`, no assigned HOME, and no active customer interaction are eligible to become builders.

### Founding and expanding settlements

- An eligible homeless villager can create a settlement record and begin a starter home. The founder receives that bed immediately and keeps it while returning for the first profession house, allowing the vanilla night schedule to send it home.
- The founding sequence attempts one common house, then a professional house; the first professional project prioritizes a smithing-related building.
- Nearby homeless unemployed villagers may join an active founding project until `maxBuildersPerVillage` is reached.
- `maxBuildersPerVillage` controls how many villagers may work at once; it does not limit or cancel entity spawning from spawn eggs. For reliable spawn tests, use separate unoccupied blocks.
- Material planning and broad surface searches are rate-limited and cache their selected target so large modpacks do not trigger a full recipe/source scan every server tick.
- After the founding sequence, house selection follows the normal two-common-houses-to-one-professional-house ratio, accounting for projects already in progress.
- A vanilla meeting-point template with a bell is placed when possible. Its pedestal consumes one gold block. A settlement leaves its founding state only when a bell has been registered and all villagers detected in the loaded scan area have a HOME memory pointing to a bed block (verified when that bed chunk is loaded).
- A builder's temporary profession is removed when the job completes or is abandoned.

### Houses by biome

The mod loads structure templates from Minecraft's own `minecraft:village/...` structure data. It selects plains, desert, savanna, taiga or snowy layouts based on the current biome. Where Minecraft has no dedicated village layout (for example, a regular Forest/Oak Forest, Birch Forest, Dark Forest, Jungle, Mangrove Swamp or Cherry Grove), the plains layout is used as the structural base but its vanilla wooden blocks are converted to the local tree family. Oak Forest therefore requests oak logs, not spruce logs; Taiga requests spruce; Birch Forest requests birch; Dark Forest requests dark oak; other supported biome families use their matching wood.

The conversion covers logs, wood, planks, stairs, slabs, doors, trapdoors, fences, gates, signs and other vanilla wood-family blocks. Existing state properties such as axis, facing, half and waterlogging are preserved. The template loader uses one palette per structure, since a structure's palettes represent alternative block variants rather than additive layers. This prevents duplicated block placement.

### Roads, trees and terrain

- A* path search connects the house entrance to the nearest village route/anchor, with its expansion limit exposed in the config.
- The builder recognizes natural trees conservatively: a candidate needs a natural-soil root, a three-block vertical trunk and a canopy of non-persistent leaves. Logs near beds, doors, workstations or block entities are protected to avoid harvesting house structures.
- A selected tree is felled by breaking every connected trunk/branch log individually with visible block damage. Leaves are left intact for vanilla decay; sapling drops are collected later. Saplings are replanted on valid soil with sufficient light, away from the active house footprint and village road. Nearby sapling item drops are planted directly if the Villager inventory is full.
- Saplings that cannot be planted immediately remain in the worker's inventory or as nearby item drops for a later planting attempt.
- Terrain preparation uses a compact 4–12 block site search around the village by default. It accepts controlled slopes (up to 6 blocks from the median floor), clears natural dirt/stone protrusions from the future house volume, cuts high columns and fills low columns with dirt/cobblestone. Total earthwork is capped at 160 block operations by default; water, block entities and existing solid/player-built structures are protected.
- New projects clear only the house footprint, level it, and build the house first. They no longer cut trees along the entire future road before construction. The access road is the final phase and removes only trees/plants that actually obstruct its blocks.
- A builder works from a validated standing position outside the plot, with fallbacks beside the next terrain operation. If the selected plot is already level, it skips the leveling trip and begins construction immediately. Unreachable tree logs or road obstacles are skipped after bounded local recovery rather than causing world-wide wandering.
- The resulting road uses vanilla `dirt_path` blocks and avoids overwriting non-replaceable structures.

### Caves and mines

- Before digging, the builder scans already-loaded chunks for safe cave-floor candidates with air for the villager, a solid floor, a ceiling and at least one side wall. During an exact material request it can also use an exposed rock face on a mountain or ravine, provided there is safe standing room.
- If a suitable exposed stone/ore target is found, it moves to that target and mines the block using the held pickaxe for vanilla loot-table evaluation.
- If no cave target is found, it opens a descending stair tunnel. The tunnel is 1×2 clear space, uses cobblestone for an unsupported floor when needed, and periodically places log supports if the worker has logs available.
- Mine entrances are saved as part of the settlement and are excluded from nearby house-site selection.
- Later projects can reuse registered mine entrances. Active mine entrances and current resource targets are reserved against other workers.
- Cave scans do not force-load chunks.

### Block harvesting, tools and exact material planning

- Tree logs, cave stone, ores and surface materials use visible vanilla block-breaking progress. Leaves are never harvested by the builder. The time is calculated from block hardness, tool mining speed and suitability; damageable tools lose durability when a block is harvested. Loot tables receive the actual held tool, so stone drops cobblestone and pickaxe-gated ores produce their normal drops.
- Wood is requested by exact item and may only be sourced from a verified natural tree. The builder never mines arbitrary log blocks as a wood resource. Natural logs can be harvested by hand when the worker has no axe; this is the bootstrap path that lets it collect wood before crafting its first tools. Tree search advances through every column in a center-out spiral, in bounded slices of 1,024 columns, and expands its radius only after the previous square has been searched. Tree felling collects the whole selected tree, rather than leaving floating trunks. A conservative nearby-building check protects trees close to beds, doors, workstations and block entities.
- The selected house template is counted as an item plan. Before gathering an item type, the builder counts every still-placeable occurrence of that item remaining in the blueprint and resolves the recipe ingredients for the total. Existing inventory is deducted, and the saved request count prevents repeated one-at-a-time collection loops. One item is consumed only when its block is placed. Upper door halves and bed head halves do not consume a second item; occupied non-replaceable positions are skipped without consuming materials.
- Existing inventory is used first. When an item is missing, the builder follows registered crafting recipes recursively and collects only the missing ingredients. Recipe output is produced in normal recipe batches (for example, a door recipe yields three doors), so a small remainder can be carried into later placements or future jobs.
- Recipe-based material sourcing includes wood from verified natural trees, stone and ore from exposed caves/mines or safe exposed rock faces, sand and clay from terrain, and wool from shearable sheep. During exact material requests, mining collects only the requested item to avoid filling the worker inventory with unrelated tunnel debris. Sheep are only sheared after the worker has crafted and equipped shears; wool can then be crafted into beds through the registered recipe system. Grass-block placements are planned as dirt items because vanilla grass blocks drop dirt without Silk Touch.
- Registered smelting recipes (including sand-to-glass and ore processing) are simulated over time and consume coal/charcoal fuel. Coal/charcoal are reserved as processing fuel; the mod does not burn the house's planks, logs or sticks.
- Crafting and smelting are recipe-driven controller actions with particles, time and ingredient consumption. They do not yet operate a visible crafting-table or furnace block with a physical GUI.

Block-break progress advances once per game tick and uses the held tool's `getMiningSpeedMultiplier`, suitability and block hardness; the legacy `blockBreakBaseTicks` setting is retained for config compatibility but no longer overrides vanilla tool speed. A missing/broken axe, pickaxe or shovel returns the task to the material planner before another block is mined. Navigation failures reject the failed target and select a bounded fallback rather than retrying the same destination forever.

The builder checks its inventory for an appropriate axe, pickaxe or shovel and equips the strongest available one. Before extracting or crafting a missing item, it searches for an employed villager with a matching, currently available trade output within 40 blocks (configurable). The builder walks to that professional and receives the offered item for free: no emeralds, barter ingredients or merchant XP are exchanged. One offer use is reserved per transfer so stock remains finite and can return through normal villager restocking. This applies to all professional villagers and any matching output in their current offers, including tools, food, crops, wool and other recipe inputs. If no suitable offer exists or the provider cannot be reached, the recursive planner falls back to natural collection, crafting, stonecutting or smelting. Legacy `blacksmithToolUpgrades` config is retained for compatibility but the old iron-ingot-consuming upgrade behavior has been replaced by this system.

### Builder appearance

The mod includes its own 64×64 profession overlay: a slate-blue hard hat, muted teal workwear and brown tool-belt details. The texture is stored in both villager and zombie-villager profession texture paths. The mod must also be installed on clients so they can render the custom profession skin.

### Bed-independent reproduction

Two adult villagers can reproduce without a free bed when both meet the vanilla readiness and food conditions. The mod still applies a parent cooldown and the vanilla baby growth duration by default. The interval, partner distance, cooldown and growth time can be changed in the config.

## Configuration

On first launch the mod generates:

```text
.minecraft/config/village_expansion.json
```

On a dedicated server, the file is in the server's `config` directory. Example settings:

```json
{
  "enabled": true,
  "maxBuildersPerVillage": 20,
  "maxActiveBuildersPerDimension": 20,
  "navigationSpeed": 0.65,
  "taskTickInterval": 1,
  "builderTaskUpdatesPerTick": 2,
  "builderTaskGlobalIntervalTicks": 1,
  "builderTaskMinIntervalTicks": 2,
  "assignmentScanInterval": 40,
  "spatialSearchIntervalTicks": 8,
  "lazyPlanningStepIntervalTicks": 1,
  "lazyPlanningRecipeEntriesPerTick": 128,
  "retryDelayTicks": 600,
  "dangerRadius": 12,
  "dangerPauseTicks": 120,
  "sleepAtNightWhenHomeExists": true,
  "caveSearchRadius": 32,
  "caveSearchDepth": 48,
  "caveSearchIntervalTicks": 100,
  "mineMaxLength": 64,
  "mineSupportInterval": 4,
  "blockBreakBaseTicks": 28,
  "particleIntervalTicks": 20,
  "mineExclusionRadius": 24,
  "stoneSearchFailuresBeforeMine": 5,
  "mineMinimumDistanceFromVillage": 20,
  "houseSpacing": 12,
  "blacksmithToolUpgrades": true,
  "professionalResourceSharing": true,
  "professionalResourceSearchRadius": 40,
  "woodRequired": 0,
  "stoneRequired": 0,
  "woodUnitsPerLog": 4,
  "woodSearchRadius": 32,
  "replantSaplings": true,
  "saplingPlantRadius": 32,
  "saplingMinimumSpacing": 8,
  "showBuilderTaskLabel": true,
  "materialSearchRadius": 28,
  "materialSearchIntervalTicks": 20,
  "useRecipeBookForCrafting": true,
  "maxPathSearchNodes": 300,
  "buildBlockDelayTicks": 2,
  "navigationStallTimeoutTicks": 100,
  "navigationReassertIntervalTicks": 40,
  "navigationMaxRepaths": 4,
  "noResourceSearchLimit": 6,
  "houseSearchMinRadius": 4,
  "houseSearchMaxRadius": 12,
  "houseSearchStep": 2,
  "maxSiteTerrainVariation": 6,
  "maxSiteTerraformBlocks": 160,
  "breedingScanIntervalTicks": 200,
  "breedingPartnerRange": 8.0,
  "breedingParentCooldownTicks": 6000,
  "babyGrowthTicks": 24000
}
```

All values are clamped to safe ranges when loaded. The most useful controls are:

Version 1.4.13 automatically migrates older performance profiles (`performanceTuningVersion` below 9) to responsive defaults: two construction updates per world tick, a two-tick per-worker minimum, one-tick material-planning phases, 128 recipe index entries per tick and a 20-tick natural-resource retry. The planner also prefers a supported direct source (tree, mine, crop, clay, wool, etc.) over modded recipe alternatives, and a recursive-budget timeout can no longer mark an unverified recipe as usable.

| Setting | Purpose |
|---|---|
| `maxBuildersPerVillage` | Maximum simultaneous workers in one settlement |
| `maxActiveBuildersPerDimension` | Hard dimension-wide limit, including tasks saved in unloaded chunks (default: 20) |
| `taskTickInterval` | How often the mod controller runs (default: every game tick) |
| `builderTaskMinIntervalTicks` | Minimum delay between custom task updates for the same builder (default: 2 ticks; round-robin still limits total work) |
| `builderTaskGlobalIntervalTicks` | Minimum delay between globally scheduled builder task slices (default: 1 tick) |
| `spatialSearchIntervalTicks` | Minimum delay between expensive world-resource scans in one dimension (default: 8 ticks) |
| `navigationSpeed` | Villager navigation speed while moving between work positions |
| `dangerRadius` | Distance at which hostile mobs interrupt work |
| `dangerPauseTicks` | Delay before a builder resumes after danger |
| `sleepAtNightWhenHomeExists` | Allows builders with a HOME memory to sleep normally |
| `caveSearchRadius` / `caveSearchDepth` | Cave resource search area |
| `caveSearchIntervalTicks` | Delay between cave scans |
| `mineMaxLength` / `mineSupportInterval` | Stair mine length segment and log support spacing |
| `stoneSearchFailuresBeforeMine` | Completed unsuccessful stone scans before opening a dedicated mine (default: 5) |
| `mineMinimumDistanceFromVillage` | Minimum horizontal distance from the village centre to a mine entrance (default: 20; values below 20 are clamped) |
| `blockBreakBaseTicks` | Legacy config key retained for compatibility; mining duration now follows block hardness and the equipped tool |
| `woodSearchRadius` | Search radius for tree logs |
| `maxPathSearchNodes` | Upper bound for road pathfinding work |
| `buildBlockDelayTicks` | Delay between placed house blocks |
| `navigationStallTimeoutTicks` | Time without measurable progress before repathing |
| `navigationReassertIntervalTicks` | Maximum interval between construction-route enforcement checks |
| `navigationMaxRepaths` | Alternate route attempts before a warning/reset |
| `noResourceSearchLimit` | Failed searches before the worker expands its search radius or switches to an alternative resource route |
| `mineExclusionRadius` / `houseSpacing` | Prevents houses from crowding mines or other houses |
| `professionalResourceSharing` | Allows builders to request matching offer outputs from employed villagers for free |
| `professionalResourceSearchRadius` | Maximum distance to search for professional suppliers (default: 40 blocks) |
| `breedingPartnerRange` / `breedingScanIntervalTicks` | Bedless breeding search settings |

Restart the world/server after changing the config so all settings are reloaded cleanly.

## Build with GitHub Actions (no local Java/Gradle install)

1. Create a repository at [github.com/new](https://github.com/new).
2. Extract this ZIP and upload **all files and folders**, including the hidden `.github` directory, to the repository root.
3. Commit and push to `main` or `master`, or open **Actions → Build Fabric mod → Run workflow**.
4. Wait for the workflow to finish. If it succeeds, open the run and download the artifact named `village-expansion-1.3.4` from **Artifacts**.
5. Inside the artifact, use `village-expansion-1.3.4.jar` (not the `-sources.jar`).

The workflow runs Java 21, Gradle 8.12 and `gradle clean build` on GitHub's runner.

## Install

- Install Fabric Loader and Fabric API for Minecraft 1.21.1.
- Put `village-expansion-1.3.4.jar` in the `mods` folder.
- For multiplayer, install the mod on the server and on each client that needs to see the custom builder texture.
- Start a new test world or back up an existing world before enabling a new major version.

## Save compatibility and known limitations

- Active worker task snapshots are stored in the dimension's `PersistentState`. The project stage, progress counters, dedicated stone-search failure count, road positions, plot-tree targets, partial tree-cut list and indexes, terrain operations, current resource target, pending recipe item and exact pending quantity, supplier UUID/visit timing/rejected supplier cooldown, smelting timer/fuel, active mining target, mine entrance and mine direction are serialized. Task snapshots are refreshed every 20 world ticks and restored when the villager is loaded again.
- The save loader can read the older task format and rebuilds missing work lists for those older snapshots.
- If the server crashes between snapshots, up to roughly one second of task bookkeeping can roll back. Already placed blocks remain in the world and are not overwritten if they are no longer replaceable.
- A mine is a functional generated-by-the-mod stair tunnel with periodic log supports, not a full copy of naturally generated mineshafts.
- Recipe batch sizes can leave a small surplus (for example, crafting one door produces the recipe's full batch). Leftovers are retained for later placements/jobs rather than discarded.
- Smelting is simulated by the task controller using registered vanilla smelting recipes and timed fuel consumption; the Villager does not walk to and operate a physical furnace block. Crafting similarly uses recipe data rather than a visible crafting-table interface.
- Professional resource sharing is described in the 1.3.2 and 1.3.3 sections below; the legacy `blacksmithToolUpgrades` config option is retained only for compatibility.
- Tree recognition is intentionally conservative. It filters persistent leaves and protects nearby beds, doors, workstations and block entities. Minecraft does not store a universal “player placed” flag for every block; a player-built log structure deliberately shaped like a natural tree and using non-persistent leaves can still resemble a tree to a block-based heuristic.
- Cave searches only inspect loaded chunks, and professional structures fall back to a compact blueprint when a matching biome template is missing.
- The founding completion check sees villagers currently loaded in its scan area. A villager in an unloaded chunk is evaluated once it becomes loaded in that area.
- This release is configured for Minecraft 1.21.1. Later Minecraft versions require their own mappings/dependency update and build verification.
- The source has not been compiled or play-tested inside Minecraft in the creation environment. Treat the GitHub Actions build and in-game test as release verification steps.

## License

MIT. See `LICENSE`.


## Third-party mod compatibility

Third-party profession houses can be configured through JSON. See [docs/MOD_COMPATIBILITY.md](docs/MOD_COMPATIBILITY.md) and `examples/compat/examplemod.json`. Compatible entries require the target mod, a registered Villager profession, a workstation block and an NBT structure template containing both a bed and that workstation. Compatibility files are optional; no hard dependency on the third-party mod is added.


## Resource acquisition fixes (1.3.0)

- Clay-ball requests now search clay deposits under rivers and lakes and select a nearby dry shoreline work position. The builder no longer relies on a dry stand position directly adjacent to underwater clay.
- Flower items used as recipe ingredients are now collectible by matching the requested item to its corresponding flower block. This supports dandelions and other dye flowers without making decorative flower placements mandatory in house templates.
- Save migration no longer clears flower requests. A pending dandelion/dye ingredient survives world reloads, while non-flower decorative plants such as short grass, ferns, vines and snow are still excluded from construction material requests.
- Coal searches include exposed mountain faces above the builder as well as lower cave levels. During a self-dug mine, the builder checks the newly exposed tunnel walls for the exact requested ore after each completed stair step, rather than relying only on a broad scan every third step.
- Direct resource collection now picks up only the requested item, preventing unrelated block drops from filling the villager inventory during clay/flower collection.

### Validation
- Source checks, metadata/JSON checks and ZIP integrity are included in this package. A real Fabric compile and Minecraft simulation still require GitHub Actions and an in-game test world. Use [docs/TEST_SCENARIOS_1.3.0.md](docs/TEST_SCENARIOS_1.3.0.md) for the new regression cases.


## General material objective planner (1.3.1)

- Every requested blueprint item is evaluated through the active world's registered crafting, standard smelting and stonecutting recipes, not through a per-item crafting list. Recipe outputs are indexed once per RecipeManager; alternative recipes and ingredient variants are scored by estimated acquisition cost.
- Ingredient alternatives prefer a currently accessible peaceful/natural source, but an item with no nearby hit may remain a candidate because the executor can widen its search radius. When direct acquisition is estimated cheaper than processing, the builder tries it first; after repeated failed source searches it can fall back to a recipe route. The planner rejects dependency cycles and chains deeper than 12 levels.
- Dependency goals are ordered deepest-first and kept in the saved `materialGoalStack`. A prerequisite can consume intermediate materials (for example, planks for sticks) before the parent recipe is resumed; the planner accounts for selected duplicate ingredients and rechecks inventory before crafting.
- Tool requirements are resolved as normal goals. Pickaxe tier is checked before mining, axes are crafted before stripping logs, and shears are crafted before sheep wool is collected. The recipe graph can therefore expand from a requested block into tools, planks, sticks, crafting tables, furnaces, ore, fuel and natural resources.
- Stonecutting recipes plan a retained stonecutter item and the selected input. Standard smelting recipes plan a retained furnace item, their input and coal/charcoal fuel. Stonecutting and crafting are immediate recipe-driven simulations; smelting remains timed. None of these actions places or opens a physical workstation GUI.
- Safe source adapters cover natural trees/logs, stripped logs, supported stone and ore drops, clay deposits, flowers, mature common crops, sheep wool and selected terrain blocks. Hostile-mob drops are not chosen as fallbacks.
- If no registered recipe or supported peaceful/natural source exists, the planner logs the missing item and keeps the project state instead of inventing a drop. Decorative vegetation remains optional.

### Scope and limitations

This is generic planning across registered vanilla/datapack **crafting**, standard **smelting** and **stonecutting** recipes, combined with the mod's supported world-source adapters. It is not an oracle for every possible loot table or every recipe category: smithing, brewing, blasting, smoking, campfire cooking, player trades and arbitrary third-party gathering mechanics are not yet executable acquisition routes. A modded item that has only a custom loot-table drop needs a source adapter. Mature crops are harvested when present; the builder does not yet create/maintain farms as a universal crop provider.

See [docs/MATERIAL_ACQUISITION_PLANNER_1.3.1.md](docs/MATERIAL_ACQUISITION_PLANNER_1.3.1.md) for the resolution rules and [docs/TEST_SCENARIOS_1.3.1.md](docs/TEST_SCENARIOS_1.3.1.md) for regression cases.

### Verification status

The package includes source-level checks, JSON/workflow consistency checks and ZIP integrity checks. A Fabric compile and gameplay simulation still need to be run by GitHub Actions and in a Minecraft test world.

## Decorative vegetation is optional (1.2.7)

- Decorative plants present in vanilla structure palettes are not counted as house materials. The builder skips them instead of requesting items such as `short_grass`.
- If a saved villager was already waiting for `short_grass` or another recognized plant item, the request is cleared on restore and the villager resumes its saved construction stage.
- This uses the existing vegetation classifier, covering flowers, short/tall grass, ferns, dead bushes, snow layers and vines.

### Validation
- Source syntax/delimiter screening, metadata checks, JSON parsing and ZIP integrity checks are included in release preparation. This environment does not include Gradle or a Minecraft/Fabric runtime, so a real build and in-game simulation remain pending GitHub Actions and Minecraft. Use [docs/TEST_SCENARIOS_1.2.7.md](docs/TEST_SCENARIOS_1.2.7.md) for regression tests.

## Mining recovery, loot correctness and safer construction (1.2.6)

- Mining no longer calls `world.breakBlock(..., true, villager)` as if that supplied the held pickaxe. The block is removed without automatic drops, then its vanilla loot table is evaluated with the captured tool stack. This fixes the underlying cause for cobblestone and supported ore drops, not just the displayed item name.
- Exact material requests may target safe exposed stone faces on a mountain or ravine as well as cave walls. When digging a stair mine, only the requested item is collected, so unrelated dirt/gravel drops cannot fill the villager inventory and prevent cobblestone collection.
- Stone/cobblestone searches now get five completed attempts before the builder starts its own descending stair mine. Each miss expands the next search radius by 8 blocks (up to 96). The failed-search count is saved, so restarting the world does not reset the five-attempt sequence.
- The builder walks to a mine entrance at least 20 horizontal blocks from the village centre before digging. Existing mine entrances closer than 20 blocks are not reused. Both values have config entries; the mine-distance setting cannot be reduced below 20.
- Blueprint planning maps `grass_block` to the actual harvested item, `dirt`. Saved tasks from 1.2.3 or earlier that were waiting for the impossible `grass_block` item are migrated to `dirt` when restored.
- Repeated misses expand wood, dirt, wool, surface-material and cave searches up to 96 blocks. Workers keep their active house project instead of abandoning it after a fixed number of failed searches.
- If the leveling stage cannot find enough surface dirt after expanding its search, it obtains stone through the cave/stair-mine route and uses that as a fill substitute rather than skipping the terrain operation.
- Newly selected mine entrances must have a dry, clear two-block-high approach and are checked against other builders' reserved mine entrances. Registered mine locations continue to be reused by later projects.
- Construction workers now find standing positions on any solid floor, including the placed wooden floor inside a house. They do not place a block inside a living entity's hitbox; if a villager or mob occupies the target, the builder waits and retries.
- Wool collection now requires crafted shears. The shears are equipped for the action and their durability is applied to the shears, not the builder's currently equipped pickaxe or axe.
- A new settlement's meeting point requires one gold block for the bell pedestal. The builder uses the registered recipe/mining/smelting chain to obtain it before registering the bell.
- Founding completion checks verify that each loaded villager's remembered HOME position is a bed block. When a remembered bed is broken in a loaded chunk, the villager's stale HOME memory is cleared so it can be assigned a replacement home.

### Validation
- Source-level checks, release metadata checks and ZIP integrity checks are run during packaging. This environment does not have Gradle or a Minecraft/Fabric runtime and cannot reach the Gradle distribution service, so the Fabric compile and in-game spawn/biome simulations still need to run through GitHub Actions and Minecraft. Use [docs/TEST_SCENARIOS_1.2.7.md](docs/TEST_SCENARIOS_1.2.7.md) as the manual acceptance checklist.

## Material gathering recovery (1.2.3)

- Ore materials such as coal that are required by crafting or smelting recipes now use the mining route instead of being searched only in the shallow surface layer. If the requested resource is not exposed in a nearby cave, the builder can continue its stair mine and search the newly exposed walls for that exact resource.
- While waiting for a material, the label above the villager shows the missing item and quantity, such as `[VE] Faltando: coal x1`.

## Builder task labels and tree replanting (1.2.2)

- Leaves are left intact after the trunk is felled; Minecraft handles their natural decay, and sapling drops are collected later.

- Unnamed active builders display a temporary `[VE]` name label above their head with their current task (Portuguese). Set `showBuilderTaskLabel` to `false` to disable it. Existing player-assigned custom names are not overwritten.
- Builders no longer break leaf blocks. Leaves decay naturally after logs are removed; sapling item drops are collected opportunistically and re-planted.
- Sapling planting spots are randomized across suitable dirt/grass ground within `saplingPlantRadius`, with `saplingMinimumSpacing` blocks between saplings (default: 8).
- Sapling planting is opportunistic and does not block house construction if no saplings drop or no valid planting spot is available.
## Objective planning and recovery (1.3.0)

- Blueprint materials are treated as goals and resolved through registered crafting/smelting recipes. Missing ingredients are requested recursively; parent item and quantity are retained while prerequisite ingredients are gathered.
- Tool prerequisites are now explicit. A crafting table is retained before tool crafting; coal/stone collection uses at least a wooden pickaxe, iron/copper/gold/lapis/redstone uses a stone pickaxe, and diamond/emerald uses an iron pickaxe.
- Stripped-log requests resolve to the matching natural log species. The builder obtains an axe, strips the trunk block, then collects the stripped log.
- Wool requests prefer shearable sheep and crafted shears. Hostile mob drops are not used as a fallback for bed materials.
- Stone collection remembers a bounded quarry centre and works locally before choosing a new source, reducing scattered holes. Quarry state survives save/reload.
- A limited navigation rescue can place one dirt/cobblestone step when a higher target is unreachable, after checking support, clearance and nearby entities.
- The house is now built before its access road. The road follows the template's entrance-side goal and is widened to three blocks.
- Existing 1.2.x tasks saved during `BUILD_PATH` return to unfinished house construction before the road resumes.

See [docs/OBJECTIVE_PLANNER_1.3.0.md](docs/OBJECTIVE_PLANNER_1.3.0.md) for the objective/source policy and [docs/TEST_SCENARIOS_1.3.0.md](docs/TEST_SCENARIOS_1.3.0.md) for manual regression tests.

### Verification status

The source package has been checked for Java syntax-level errors, JSON validity, workflow/version consistency and ZIP integrity. A full Fabric compile and Minecraft simulation were not run in the creation environment; use GitHub Actions and the in-game scenarios before treating this release as gameplay-verified.


## Professional resource sharing (1.3.2)

- Before gathering or crafting a missing material, the recursive planner checks employed, adult villagers with a profession inside the configured radius (40 blocks by default). It matches the output of their currently available offers to the requested item; equal-or-better tools of the same family also satisfy tool prerequisites.
- The builder navigates to the nearest matching professional and receives the output without paying emeralds, barter ingredients or granting merchant XP. One offer use is reserved per output stack so stock remains finite and can become available again through vanilla restocking.
- The provider can be a blacksmith, farmer, shepherd or any other non-nitwit profession; profession name is not hard-coded to the requested item. The offer output must match the goal. If no provider has suitable stock, is reachable or has room for the delivery, the planner resumes self-acquisition.
- `professionalResourceSharing` toggles the feature and `professionalResourceSearchRadius` configures the search radius (default 40, clamped to 8–128).
- This is a free, mod-defined transfer based on the villager's offer output. It does not execute the vanilla merchant transaction and therefore does not consume the offer's input items or grant trade XP. The offer's use counter is advanced to avoid unlimited duplication before restocking.

### Validation
- Static source, JSON, workflow and ZIP checks are included. GitHub Actions must still confirm the Fabric build; in-game scenarios are documented in [docs/TEST_SCENARIOS_1.3.2.md](docs/TEST_SCENARIOS_1.3.2.md).




## 1.3.4 build fix

- Declared the professional-supplier visit and rejection state directly on `BuilderTask`, matching the fields already written and restored by `VillageBuilderController`.
- The fields include the active provider UUID, visit start tick, rejected provider UUID, rejected item and rejection expiry tick.
- This fixes the 41 `cannot find symbol` compiler errors reported by GitHub Actions for the 1.3.3 professional-resource-sharing change.
- Supplier visit state remains persisted in the task NBT; no save migration is required because missing NBT keys use their normal empty/default values.

## Professional supply acquisition hardening (1.3.3)

- Free supply sharing remains available from any adult, employed, non-nitwit villager whose active offer output matches the builder's current material goal. The scan radius defaults to 40 blocks and remains configurable.
- A matching offer can be used multiple times in one visit while stock remains; each output stack reserves exactly one offer use. This avoids unnecessary repeat trips when the requested quantity exceeds one trade output stack.
- Tool matching accepts equal-or-better tools only from the same family (pickaxe for pickaxe, axe for axe, shovel for shovel). An unrelated tool can never satisfy the prerequisite.
- Providers without a matching active offer, with exhausted offers, outside the radius, inaccessible for too long or unable to transfer because of inventory capacity are skipped; the builder falls back to its own acquisition plan.

### Regression checks
See [docs/TEST_SCENARIOS_1.3.3.md](docs/TEST_SCENARIOS_1.3.3.md). These are manual in-game scenarios and have not been simulated in this environment.

## Lazy Planning (1.3.7)

Material planning is time-sliced. The controller does not evaluate the full recipe tree during the same tick that a material is requested. It advances one bounded phase at a time in a global round-robin queue: direct source evaluation, crafting, stonecutting, and smelting. The recipe index is built by iterating the recipe manager key stream in small batches, rather than creating full filtered lists for each recipe type. This avoids a single large registry scan during the first material request.

New configuration values are written automatically to `config/village_expansion.json`:

- The original 1.3.7 defaults were `lazyPlanningStepIntervalTicks = 4` and `lazyPlanningRecipeEntriesPerTick = 32`.
- The historical 1.3.9 ultra-low-CPU profile used `lazyPlanningStepIntervalTicks = 40` and `lazyPlanningRecipeEntriesPerTick = 2`. Version 1.4.13 replaces that profile with bounded two-worker scheduling, one-tick planning phases and 128 recipe entries per tick so pending material decisions cannot be starved by construction updates.
- The first value controls the minimum ticks between phases of a material plan. Increasing it lowers planning load but delays acquisition decisions.
- The second value caps recipe registry entries inspected while the shared index is being built. Lower values smooth indexing further but delay the first recipe-based plan.

The historical 1.3.9 controller advanced one material-planning phase per world/controller tick, separated recipe comparisons by 40 ticks and indexed two recipes per tick. Version 1.4.13 replaces those timings with one-tick planning phases, two fair planning slices per tick, and 128 recipe index entries per tick.

## Historical ultra-low-CPU controller (1.3.9)

- The controller itself runs every 2 game ticks by default. It advances at most one expensive construction task slice every 2 ticks, in round-robin order.
- Each builder has a 40-tick minimum interval between custom task slices. With 20 active builders, each receives roughly one custom action every 2 seconds; construction is slower, but one builder cannot monopolize the main thread.
- `maxActiveBuildersPerDimension` is a hard limit of 20 active projects for the whole dimension, including saved tasks whose entity chunks are unloaded. `maxBuildersPerVillage` remains an additional per-settlement limit.
- Vanilla villager Brain, navigation, looking, and movement continue to tick at the normal game rate. The mod only throttles its own construction state machine and destination reassertion.
- The controller uses Fabric `ServerEntityEvents.ENTITY_LOAD` / `ENTITY_UNLOAD` to maintain a loaded-villager cache. It and the breeding fallback no longer run full-world entity polling.
- Resource search permits are spaced 20 ticks apart per dimension. Material scans use bounded samples; tree canopy/protection checks use sparse probes; exposed-cave searches use a smaller sample grid.
- Tree discovery, tree cutting, plot vegetation clearing and blueprint-material counting remain incremental. Search targets and material quotas are cached.
- The historical profile advanced lazy recipe planning every 40 ticks and indexed at most 2 recipe entries per tick. This is no longer the default after version 1.4.13.
- A* road discovery is capped at 300 expanded nodes. Task, assignment and controller slices log warnings at 10 ms.

This historical profile intentionally traded construction speed for lower main-thread overhead. Its settings are retained here for context only; 1.4.13 uses a responsive bounded scheduler. It does not remove the cost of Minecraft's own entity ticking, vanilla pathfinding, chunk simulation, other mods, or networking; zero total server cost is not achievable.


## 1.4.1 — House-first clearing and bounded local terraforming

- New builders start at `LEVEL_SITE`, not `CLEAR_PATH`. They only clear trees and vegetation whose trunks/canopy intersect the house footprint, then level the lot and build the house.
- The future access road is processed after the house. Trees are cut only when their trunks physically obstruct a road block; unreachable road trees are skipped locally rather than sending the builder away from the house.
- Existing 1.4.0 tasks saved in `CLEAR_PATH` migrate to `LEVEL_SITE`, discard the old route-wide tree list and resume local plot preparation.
- Default house search is reduced to a 4–12 block radius with 2-block bands. The footprint accepts up to 6 blocks of terrain variation and up to 160 cut/fill/clear operations, using dirt or cobblestone collected from the cuts before requesting more dirt.
- If the perimeter work position is blocked, the builder tries a standing cell beside the next terrain operation and then a compact fallback around the plot; it never expands into a world-wide search for a work position.
- Assignment retry after an unsuccessful local candidate slice follows the assignment interval instead of a fixed 100-tick delay.

## 1.4.0 — Compact site selection and adaptive terrain clearing

- House plots are searched close to the village (default radius 4–18 blocks) instead of making workers roam while trying to find a naturally perfect clearing.
- The site selector now accepts controlled slopes and prepares cut/fill operations, with a default limit of 4 blocks of height variation and 96 earthwork block operations.
- Builders use an accessible work position outside the footprint; a flat plot with no leveling operations skips the leveling trip and proceeds directly to construction.
- Tree clearing searches adjacent and radius-two standing positions. An unreachable individual log is skipped after bounded recovery attempts so one obstructed tree cannot keep the entire project in CLEAR_PATH forever.
- If a work position becomes unreachable, the builder rejects it and selects another nearby stand instead of repeatedly restarting the same navigation route.
- Existing construction task saves remain compatible; new work-stand caches are runtime-only and are safely rebuilt after reload.


## Compact site selection and adaptive clearing (1.4.1)

New configuration keys:

```json
{
  "houseSearchMinRadius": 4,
  "houseSearchMaxRadius": 12,
  "houseSearchStep": 2,
  "maxSiteTerrainVariation": 6,
  "maxSiteTerraformBlocks": 160
}
```

The site selector no longer requires a naturally flat, completely clear footprint. It evaluates a small local radius, allows bounded natural terrain changes, and creates incremental cut/fill operations. If no leveling is necessary, the worker moves directly to construction.


## 1.4.10 compile fixes

- Use the `ServerWorld` local in `afterVanillaBrainTick` when hiding active block-breaking progress.
- Declare the persisted `smeltWorkstationPos` field on `BuilderTask`, matching its existing NBT save/load and furnace-lease usage.
