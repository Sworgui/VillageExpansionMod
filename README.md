# Village Expansion 1.2.6

Fabric mod that lets unemployed villagers start settlements, prepare land, gather materials, dig mines and build village-style homes. The project currently targets **Minecraft 1.21.1 only**.

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16.14 or a compatible later loader for 1.21.1
- Fabric API for Minecraft 1.21.1
- Java 21

This is a source project. It is not a precompiled JAR. The included GitHub Actions workflow builds the mod and uploads the JAR as an artifact.

## Main behavior

### Construction priority, danger and sleep

- The vanilla Villager Brain continues ticking. After each Brain tick, the mod compares its active navigation target with the current construction target and reapplies the construction route if vanilla wandering has replaced it.
- If a hostile mob enters the configured danger radius or the villager is attacked, construction navigation priority is temporarily yielded so vanilla flee behavior can run. The same pause is applied while the villager is burning, drowning or at critically low health (25% or less), giving vanilla movement/safety goals control before construction resumes from its saved stage.
- A navigation watchdog detects lack of movement progress, retries alternate nearby standing positions, and logs repeated route failures instead of silently waiting forever.
- If a material search repeatedly fails, the worker expands its search radius (up to 96 blocks), logs the exact requested item and retries without discarding the active house project. Stone-like crafting ingredients and supported raw ores can use the underground mining route when no exposed target is found. If surface dirt is unavailable for a leveling fill, the worker can obtain stone through the mine and use it as a substitute.
- At night, a builder with an assigned `HOME` memory is allowed to use its vanilla schedule to reach and sleep in its bed. The founding villager keeps the first house bed while it returns for the starter profession house. Its construction stage resumes after morning.
- Only adult villagers with profession `none`, no assigned HOME, and no active customer interaction are eligible to become builders.

### Founding and expanding settlements

- An eligible homeless villager can create a settlement record and begin a starter home. The founder receives that bed immediately and keeps it while returning for the first profession house, allowing the vanilla night schedule to send it home.
- The founding sequence attempts one common house, then a professional house; the first professional project prioritizes a smithing-related building.
- Nearby homeless unemployed villagers may join an active founding project until `maxBuildersPerVillage` is reached.
- After the founding sequence, house selection follows the normal two-common-houses-to-one-professional-house ratio, accounting for projects already in progress.
- A vanilla meeting-point template with a bell is placed when possible. Its pedestal consumes one gold block. A settlement leaves its founding state only when a bell has been registered and all villagers detected in the loaded scan area have a HOME memory pointing to a bed block (verified when that bed chunk is loaded).
- A builder's temporary profession is removed when the job completes or is abandoned.

### Houses by biome

The mod loads structure templates from Minecraft's own `minecraft:village/...` structure data. It selects plains, desert, savanna, taiga or snowy template families based on the current biome; biomes outside those families fall back to plains. Professional houses prefer a template that contains a known vanilla workstation. If no matching professional template can be found, a compact fallback blueprint is used.

The template loader uses one palette per structure, since a structure's palettes represent alternative block variants rather than additive layers. This prevents duplicated block placement.

### Roads, trees and terrain

- A* path search connects the house entrance to the nearest village route/anchor, with its expansion limit exposed in the config.
- The builder recognizes natural trees conservatively: a candidate needs a natural-soil root, a three-block vertical trunk and a canopy of non-persistent leaves. Logs near beds, doors, workstations or block entities are protected to avoid harvesting house structures.
- A selected tree is felled by breaking every connected trunk/branch log individually with visible block damage. Leaves are left intact for vanilla decay; sapling drops are collected later. Saplings are replanted on valid soil with sufficient light, away from the active house footprint and village road. Nearby sapling item drops are planted directly if the Villager inventory is full.
- Saplings that cannot be planted immediately remain in the worker's inventory or as nearby item drops for a later planting attempt.
- Terrain preparation executes a saved sequence of leveling operations. Dirt and stone from the builder's inventory are used for simple fill work.
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
- Wood is requested by exact item and may only be sourced from a verified natural tree. The builder never mines arbitrary log blocks as a wood resource. Tree felling collects the whole selected tree, rather than leaving floating trunks. A conservative nearby-building check protects trees close to beds, doors, workstations and block entities.
- The selected house template is counted as an item plan. Before gathering an item type, the builder counts every still-placeable occurrence of that item remaining in the blueprint and resolves the recipe ingredients for the total. Existing inventory is deducted, and the saved request count prevents repeated one-at-a-time collection loops. One item is consumed only when its block is placed. Upper door halves and bed head halves do not consume a second item; occupied non-replaceable positions are skipped without consuming materials.
- Existing inventory is used first. When an item is missing, the builder follows registered crafting recipes recursively and collects only the missing ingredients. Recipe output is produced in normal recipe batches (for example, a door recipe yields three doors), so a small remainder can be carried into later placements or future jobs.
- Recipe-based material sourcing includes wood from verified natural trees, stone and ore from exposed caves/mines or safe exposed rock faces, sand and clay from terrain, and wool from shearable sheep. During exact material requests, mining collects only the requested item to avoid filling the worker inventory with unrelated tunnel debris. Sheep are only sheared after the worker has crafted and equipped shears; wool can then be crafted into beds through the registered recipe system. Grass-block placements are planned as dirt items because vanilla grass blocks drop dirt without Silk Touch.
- Registered smelting recipes (including sand-to-glass and ore processing) are simulated over time and consume coal/charcoal fuel. Coal/charcoal are reserved as processing fuel; the mod does not burn the house's planks, logs or sticks.
- Crafting and smelting are recipe-driven controller actions with particles, time and ingredient consumption. They do not yet operate a visible crafting-table or furnace block with a physical GUI.

The builder checks its inventory for an appropriate axe, pickaxe or shovel and equips the strongest available one. If a blacksmith-type villager is nearby and the builder already carries at least three iron ingots, the mod can replace the working tool with an iron tool. Ore smelting is available for recipe ingredients, but the blacksmith does not run a barter/trading interaction.

### Builder appearance

The mod includes its own 64×64 profession overlay: a yellow hard hat and orange workwear with dark tool-belt details. The texture is original and stored in both villager and zombie-villager profession texture paths. The mod must also be installed on clients so they can render the custom profession skin.

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
  "maxBuildersPerVillage": 3,
  "navigationSpeed": 0.65,
  "taskTickInterval": 1,
  "assignmentScanInterval": 100,
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
  "woodRequired": 0,
  "stoneRequired": 0,
  "woodUnitsPerLog": 4,
  "woodSearchRadius": 32,
  "replantSaplings": true,
  "saplingPlantRadius": 32,
  "saplingMinimumSpacing": 8,
  "showBuilderTaskLabel": true,
  "materialSearchRadius": 28,
  "materialSearchIntervalTicks": 100,
  "useRecipeBookForCrafting": true,
  "maxPathSearchNodes": 8000,
  "buildBlockDelayTicks": 2,
  "navigationStallTimeoutTicks": 100,
  "navigationReassertIntervalTicks": 10,
  "navigationMaxRepaths": 4,
  "noResourceSearchLimit": 6,
  "houseSearchMinRadius": 12,
  "houseSearchMaxRadius": 44,
  "houseSearchStep": 4,
  "breedingScanIntervalTicks": 200,
  "breedingPartnerRange": 8.0,
  "breedingParentCooldownTicks": 6000,
  "babyGrowthTicks": 24000
}
```

All values are clamped to safe ranges when loaded. The most useful controls are:

| Setting | Purpose |
|---|---|
| `maxBuildersPerVillage` | Maximum simultaneous workers in one settlement |
| `navigationSpeed` | Villager navigation speed while moving between work positions |
| `dangerRadius` | Distance at which hostile mobs interrupt work |
| `dangerPauseTicks` | Delay before a builder resumes after danger |
| `sleepAtNightWhenHomeExists` | Allows builders with a HOME memory to sleep normally |
| `caveSearchRadius` / `caveSearchDepth` | Cave resource search area |
| `caveSearchIntervalTicks` | Delay between cave scans |
| `mineMaxLength` / `mineSupportInterval` | Stair mine length segment and log support spacing |
| `stoneSearchFailuresBeforeMine` | Completed unsuccessful stone scans before opening a dedicated mine (default: 5) |
| `mineMinimumDistanceFromVillage` | Minimum horizontal distance from the village centre to a mine entrance (default: 20; values below 20 are clamped) |
| `blockBreakBaseTicks` | Overall block-breaking pace multiplier |
| `woodSearchRadius` | Search radius for tree logs |
| `maxPathSearchNodes` | Upper bound for road pathfinding work |
| `buildBlockDelayTicks` | Delay between placed house blocks |
| `navigationStallTimeoutTicks` | Time without measurable progress before repathing |
| `navigationReassertIntervalTicks` | Maximum interval between construction-route enforcement checks |
| `navigationMaxRepaths` | Alternate route attempts before a warning/reset |
| `noResourceSearchLimit` | Failed searches before the worker expands its search radius or switches to an alternative resource route |
| `mineExclusionRadius` / `houseSpacing` | Prevents houses from crowding mines or other houses |
| `breedingPartnerRange` / `breedingScanIntervalTicks` | Bedless breeding search settings |

Restart the world/server after changing the config so all settings are reloaded cleanly.

## Build with GitHub Actions (no local Java/Gradle install)

1. Create a repository at [github.com/new](https://github.com/new).
2. Extract this ZIP and upload **all files and folders**, including the hidden `.github` directory, to the repository root.
3. Commit and push to `main` or `master`, or open **Actions → Build Fabric mod → Run workflow**.
4. Wait for the workflow to finish. If it succeeds, open the run and download the artifact named `village-expansion-1.2.6` from **Artifacts**.
5. Inside the artifact, use `village-expansion-1.2.6.jar` (not the `-sources.jar`).

The workflow runs Java 21, Gradle 8.12 and `gradle clean build` on GitHub's runner.

## Install

- Install Fabric Loader and Fabric API for Minecraft 1.21.1.
- Put `village-expansion-1.2.6.jar` in the `mods` folder.
- For multiplayer, install the mod on the server and on each client that needs to see the custom builder texture.
- Start a new test world or back up an existing world before enabling a new major version.

## Save compatibility and known limitations

- Active worker task snapshots are stored in the dimension's `PersistentState`. The project stage, progress counters, dedicated stone-search failure count, road positions, plot-tree targets, partial tree-cut list and indexes, terrain operations, current resource target, pending recipe item and exact pending quantity, smelting timer/fuel, active mining target, mine entrance and mine direction are serialized. Task snapshots are refreshed every 20 world ticks and restored when the villager is loaded again.
- The save loader can read the older task format and rebuilds missing work lists for those older snapshots.
- If the server crashes between snapshots, up to roughly one second of task bookkeeping can roll back. Already placed blocks remain in the world and are not overwritten if they are no longer replaceable.
- A mine is a functional generated-by-the-mod stair tunnel with periodic log supports, not a full copy of naturally generated mineshafts.
- Recipe batch sizes can leave a small surplus (for example, crafting one door produces the recipe's full batch). Leftovers are retained for later placements/jobs rather than discarded.
- Smelting is simulated by the task controller using registered vanilla smelting recipes and timed fuel consumption; the Villager does not walk to and operate a physical furnace block. Crafting similarly uses recipe data rather than a visible crafting-table interface.
- Blacksmith tool upgrades require the ingots already in the builder inventory; the mod does not run barter interactions.
- Tree recognition is intentionally conservative. It filters persistent leaves and protects nearby beds, doors, workstations and block entities. Minecraft does not store a universal “player placed” flag for every block; a player-built log structure deliberately shaped like a natural tree and using non-persistent leaves can still resemble a tree to a block-based heuristic.
- Cave searches only inspect loaded chunks, and professional structures fall back to a compact blueprint when a matching biome template is missing.
- The founding completion check sees villagers currently loaded in its scan area. A villager in an unloaded chunk is evaluated once it becomes loaded in that area.
- This release is configured for Minecraft 1.21.1. Later Minecraft versions require their own mappings/dependency update and build verification.
- The source has not been compiled or play-tested inside Minecraft in the creation environment. Treat the GitHub Actions build and in-game test as release verification steps.

## License

MIT. See `LICENSE`.


## Third-party mod compatibility

Third-party profession houses can be configured through JSON. See [docs/MOD_COMPATIBILITY.md](docs/MOD_COMPATIBILITY.md) and `examples/compat/examplemod.json`. Compatible entries require the target mod, a registered Villager profession, a workstation block and an NBT structure template containing both a bed and that workstation. Compatibility files are optional; no hard dependency on the third-party mod is added.


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
- Source-level checks, release metadata checks and ZIP integrity checks are run during packaging. This environment does not have Gradle or a Minecraft/Fabric runtime and cannot reach the Gradle distribution service, so the Fabric compile and in-game spawn/biome simulations still need to run through GitHub Actions and Minecraft. Use [docs/TEST_SCENARIOS_1.2.6.md](docs/TEST_SCENARIOS_1.2.6.md) as the manual acceptance checklist.

## Material gathering recovery (1.2.3)

- Ore materials such as coal that are required by crafting or smelting recipes now use the mining route instead of being searched only in the shallow surface layer. If the requested resource is not exposed in a nearby cave, the builder can continue its stair mine and search the newly exposed walls for that exact resource.
- While waiting for a material, the label above the villager shows the missing item and quantity, such as `[VE] Faltando: coal x1`.

## Builder task labels and tree replanting (1.2.2)

- Leaves are left intact after the trunk is felled; Minecraft handles their natural decay, and sapling drops are collected later.

- Unnamed active builders display a temporary `[VE]` name label above their head with their current task (Portuguese). Set `showBuilderTaskLabel` to `false` to disable it. Existing player-assigned custom names are not overwritten.
- Builders no longer break leaf blocks. Leaves decay naturally after logs are removed; sapling item drops are collected opportunistically and re-planted.
- Sapling planting spots are randomized across suitable dirt/grass ground within `saplingPlantRadius`, with `saplingMinimumSpacing` blocks between saplings (default: 8).
- Sapling planting is opportunistic and does not block house construction if no saplings drop or no valid planting spot is available.
