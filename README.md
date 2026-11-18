# Village Expansion 1.0.0

Fabric mod that lets unemployed villagers start settlements, prepare land, gather materials, dig mines and build village-style homes. The project currently targets **Minecraft 1.21.1 only**.

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16.14 or a compatible later loader for 1.21.1
- Fabric API for Minecraft 1.21.1
- Java 21

This is a source project. It is not a precompiled JAR. The included GitHub Actions workflow builds the mod and uploads the JAR as an artifact.

## Main behavior

### Construction priority, danger and sleep

- While an active construction task is running, the mod suppresses only the villager's vanilla `Brain.tick` call. The villager's normal entity tick, navigation and movement updates continue.
- If a hostile mob enters the configured danger radius or the villager is attacked, vanilla Brain control is restored so the villager can flee. The construction task pauses for `dangerPauseTicks` and then resumes from its saved stage.
- At night, a builder with an assigned `HOME` memory is allowed to use its vanilla schedule to reach and sleep in its bed. The founding villager keeps the first house bed while it returns for the starter profession house. Its construction stage resumes after morning.
- Only adult villagers with profession `none`, no assigned HOME, and no active customer interaction are eligible to become builders.

### Founding and expanding settlements

- An eligible homeless villager can create a settlement record and begin a starter home. The founder receives that bed immediately and keeps it while returning for the first profession house, allowing the vanilla night schedule to send it home.
- The founding sequence attempts one common house, then a professional house; the first professional project prioritizes a smithing-related building.
- Nearby homeless unemployed villagers may join an active founding project until `maxBuildersPerVillage` is reached.
- After the founding sequence, house selection follows the normal two-common-houses-to-one-professional-house ratio, accounting for projects already in progress.
- A vanilla meeting-point template with a bell is placed when possible. A settlement leaves its founding state only when a bell has been registered and all villagers detected in the loaded scan area have HOME memory.
- A builder's temporary profession is removed when the job completes or is abandoned.

### Houses by biome

The mod loads structure templates from Minecraft's own `minecraft:village/...` structure data. It selects plains, desert, savanna, taiga or snowy template families based on the current biome; biomes outside those families fall back to plains. Professional houses prefer a template that contains a known vanilla workstation. If no matching professional template can be found, a compact fallback blueprint is used.

The template loader uses one palette per structure, since a structure's palettes represent alternative block variants rather than additive layers. This prevents duplicated block placement.

### Roads, trees and terrain

- A* path search connects the house entrance to the nearest village route/anchor, with its expansion limit exposed in the config.
- The builder clears leaves and plants and chops recognized tree trunks one block at a time.
- Terrain preparation executes a saved sequence of leveling operations. Dirt and stone from the builder's inventory are used for simple fill work.
- The resulting road uses vanilla `dirt_path` blocks and avoids overwriting non-replaceable structures.

### Caves and mines

- Before digging, the builder scans already-loaded chunks for safe cave-floor candidates with air for the villager, a solid floor, a ceiling and at least one side wall.
- If a suitable exposed stone/ore target is found, it moves to that target and mines the block.
- If no cave target is found, it opens a descending stair tunnel. The tunnel is 1×2 clear space, uses cobblestone for an unsupported floor when needed, and periodically places log supports if the worker has logs available.
- Mine entrances are saved as part of the settlement and are excluded from nearby house-site selection.
- Later projects can reuse registered mine entrances. Active mine entrances and current resource targets are reserved against other workers.
- Cave scans do not force-load chunks.

### Block harvesting and tools

Tree trunks, stone targets and terrain blocks use visible vanilla block-breaking progress and are removed individually. The work duration is calculated using the held item's mining-speed multiplier and block suitability. A used damageable tool loses durability when a block is successfully broken.

The builder checks its inventory for an appropriate axe, pickaxe or shovel and equips the strongest available one. If a blacksmith-type villager is nearby and the builder already carries at least three iron ingots, the mod can replace the working tool with an iron tool.

**Not yet implemented:** a complete player-like crafting and smelting pipeline. Builders do not yet collect wool and craft beds, smelt sand into glass, gather furnace fuel, or perform every intermediate recipe. Tool upgrades also do not smelt ore or trade with the blacksmith; the ingots must already be present in the worker inventory.

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
  "houseSpacing": 12,
  "blacksmithToolUpgrades": true,
  "woodRequired": 88,
  "stoneRequired": 16,
  "woodUnitsPerLog": 4,
  "woodSearchRadius": 32,
  "maxPathSearchNodes": 8000,
  "buildBlockDelayTicks": 2,
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
| `blockBreakBaseTicks` | Overall block-breaking pace multiplier |
| `woodSearchRadius` | Search radius for tree logs |
| `maxPathSearchNodes` | Upper bound for road pathfinding work |
| `buildBlockDelayTicks` | Delay between placed house blocks |
| `mineExclusionRadius` / `houseSpacing` | Prevents houses from crowding mines or other houses |
| `breedingPartnerRange` / `breedingScanIntervalTicks` | Bedless breeding search settings |

Restart the world/server after changing the config so all settings are reloaded cleanly.

## Build with GitHub Actions (no local Java/Gradle install)

1. Create a repository at [github.com/new](https://github.com/new).
2. Extract this ZIP and upload **all files and folders**, including the hidden `.github` directory, to the repository root.
3. Commit and push to `main` or `master`, or open **Actions → Build Fabric mod → Run workflow**.
4. Wait for the workflow to finish. If it succeeds, open the run and download the artifact named `village-expansion-1.0.0` from **Artifacts**.
5. Inside the artifact, use `village-expansion-1.0.0.jar` (not the `-sources.jar`).

The workflow runs Java 21, Gradle 8.12 and `gradle clean build` on GitHub's runner.

## Install

- Install Fabric Loader and Fabric API for Minecraft 1.21.1.
- Put `village-expansion-1.0.0.jar` in the `mods` folder.
- For multiplayer, install the mod on the server and on each client that needs to see the custom builder texture.
- Start a new test world or back up an existing world before enabling a new major version.

## Save compatibility and unfinished work

- Active worker task snapshots are stored in the dimension's `PersistentState`. The project stage, progress counters, road positions, tree targets and partial tree-cut list, terrain operations, current resource target, active mining target, mine entrance and mine direction are serialized. Task snapshots are refreshed every 20 world ticks and restored when the villager is loaded again.
- The save loader can read the older task format and rebuilds missing work lists for those older snapshots.
- If the server crashes between snapshots, up to roughly one second of task bookkeeping can roll back. Already placed blocks remain in the world and are not overwritten if they are no longer replaceable.
- A mine is a functional generated-by-the-mod stair tunnel with periodic log supports, not a full copy of naturally generated mineshafts.
- Resource collection is not yet a full player crafting loop; beds, glass and other template blocks are not manufactured from all constituent materials.
- Blacksmith upgrades require the ingots already in the builder inventory; the mod does not run smelting or barter interactions.
- Cave searches only inspect loaded chunks, and professional structures fall back to a compact blueprint when a matching biome template is missing.
- The founding completion check sees villagers currently loaded in its scan area. A villager in an unloaded chunk is evaluated once it becomes loaded in that area.
- This release is configured for Minecraft 1.21.1. Later Minecraft versions require their own mappings/dependency update and build verification.
- The source has not been compiled or play-tested inside Minecraft in the creation environment. Treat the GitHub Actions build and in-game test as release verification steps.

## License

MIT. See `LICENSE`.
