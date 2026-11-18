# Changelog

## 1.0.0 — Major village simulation update

### Builder behavior and scheduling
- Replaced the full `VillagerEntity.mobTick` cancellation with a redirect of the vanilla `Brain.tick` call. Construction now takes priority over Brain tasks without suppressing the rest of the villager's entity tick.
- Vanilla Brain control is returned when a hostile mob is nearby, the villager is attacked, or the configured danger pause has not elapsed. The builder resumes its saved stage after the pause.
- Builders with a remembered HOME go to sleep through the vanilla Brain at night and resume their construction task after dawn. The founder retains the bed from its first home while returning to build the founding profession house.
- Only unemployed, adult villagers without an assigned HOME can be selected as builders, except for the founding villager that is returning to complete its first profession house while keeping the starter-home bed.
- Builder cap, navigation speed, danger radius, pause duration, task intervals and construction pacing are configurable.

### Mining and resource gathering
- Cave search now filters for enclosed, dry cave-floor positions with a solid ceiling and at least one side wall, reducing false positives on open cliffs.
- If no accessible cave target is found, the worker creates a persistent descending stair mine instead of digging a shallow test hole and filling it back in.
- Mines are registered to their settlement, reused by later workers and excluded from future house plots.
- Mine entrances and active resource targets are reserved across loaded and unloaded worker tasks.
- Tree trunks, terrain blocks, stone and mining targets are broken one block at a time with visible block-breaking progress.
- Harvest duration uses the held tool's Minecraft mining-speed multiplier and suitability; tool durability is consumed when a block is successfully broken.
- Builders prefer stronger tools already in their inventory. A nearby blacksmith plus three carried iron ingots allows an upgrade to an iron tool.
- Mine entrance, current mining face, direction, progress and project targets are saved.

### Vanilla village architecture
- House selection now reads Minecraft's built-in structure templates instead of relying on a single generic 5×5 house whenever a matching template is available.
- Biome styles map to plains, desert, savanna, taiga or snowy village template families; unsupported styles use plains as a fallback.
- Professional projects prefer vanilla toolsmith, weaponsmith or armorer templates when the settlement needs its first blacksmith-type worksite.
- The founding settlement attempts to place a matching vanilla meeting-point structure with a bell.
- The settlement remains in founding state until a bell has been registered and all villagers detected in the loaded settlement area have HOME memory.

### Co-op and save persistence
- Homeless unemployed villagers can join an active founding project up to the configured builder limit.
- Active house reservations and project type reservations prevent concurrent workers from selecting the same plot or breaking the same saved resource target.
- Construction saves its stage, indices, route, tree target list, tree-cut progress, terrain operations, blueprint index, current resource/mining targets, tool restoration data and mine state.
- Saved tasks resume when their villager is loaded again; old 0.2.x task snapshots regenerate missing route data for backward compatibility.

### Appearance and configuration
- Added an original builder profession overlay featuring a yellow hard hat and orange workwear, including the zombie-villager profession texture variant.
- Added English and Brazilian Portuguese profession names.
- Added `config/village_expansion.json`, generated on first launch, including movement, resource search, mine, construction, danger, particle and breeding settings.

### Known scope boundaries in 1.0.0
- The builder does not yet run a complete vanilla crafting/furnace workflow for wool-to-bed, sand-to-glass, fuel, or recipe-based material conversion. Vanilla house templates supply their planned blocks directly, while the resource check abstracts wood and stone consumption.
- Iron tool upgrades require three iron ingots already carried by the builder and a nearby armorer/toolsmith/weaponsmith. The mod does not yet automate ore smelting or trade with that villager.
- Stair mines receive periodic log supports, but they are not a byte-for-byte reproduction of world-generated mineshafts and do not create rails, minecarts or chests.
- Cave discovery is restricted to chunks that are already loaded. It does not force-load distant terrain.
- Vanilla structure template variants are selected from the appropriate biome family when present. If a professional template with a recognizable workstation is unavailable, the mod uses its compact fallback blueprint.
- The founding completion check considers villagers loaded in the settlement's scan area. Unloaded villagers in distant chunks are not included until they load into that area.
- The source project has not been compiled or play-tested in this environment. The GitHub Actions workflow is included to run the actual Fabric build; a successful workflow and an in-game test are still required before treating this release as validated.

## 0.2.4
- Added builder particles and up to three simultaneous workers per settlement.
- Reserved active building plots and resource targets.

## 0.2.3
- Added settlement founding by an unemployed villager without an existing village.
- Added the initial common house followed by a professional house.

## 0.2.2
- Fixed NBT long-list decoding for house positions.

## 0.2.1
- Corrected the Yarn 1.21.1 `Heightmap` package import.

## 0.2.0
- Added village paths, builder appearance and bed-independent reproduction.
