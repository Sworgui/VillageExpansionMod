# Changelog

## 1.2.4 - Resource recovery and collision-safe construction

- Fixed the `Faltando: grass_block` deadlock. A blueprint now requests `dirt`, which is what vanilla grass blocks drop without Silk Touch. Existing saved tasks with a pending `grass_block` request are migrated to `dirt` on restore.
- Wood, dirt, wool, surface-material and cave searches progressively expand to a maximum radius of 96 blocks. Failed searches no longer discard the active house project.
- Terrain leveling now falls back to mining stone for fill material after repeated dirt-search misses instead of skipping the unfilled operation.
- Mine entrance selection rejects waterlogged/blocked approaches and positions occupied by living entities, and avoids entrances reserved by another active builder. Mine locations remain registered for reuse by later projects.
- Construction stand-position search now recognizes placed floors as well as natural terrain, allowing workers to finish interior blocks after the house floor and walls exist. Placement waits while a living entity occupies the destination block. The worker also yields its task to vanilla safety behavior while burning, drowning or at critically low health.
- Sheep are only sheared after the builder has crafted shears. The tool is equipped during shearing and receives the durability damage, preserving the main-hand pickaxe/axe.
- A founding settlement now obtains and spends one gold block for the meeting-point bell pedestal before registering the bell.
- Loaded villagers whose remembered HOME block is no longer a bed have that stale memory cleared, making them eligible for a replacement home. Founding completion validates loaded bed blocks rather than relying only on a HOME memory value.

### Validation
- Static source/metadata checks and ZIP integrity checks pass. Gradle/Fabric compilation and in-game testing could not be run here because Gradle and a Minecraft runtime are not installed and the Gradle distribution could not be reached. GitHub Actions remains the authoritative compile check; the multi-biome spawn scenarios still require an in-game test.

## 1.2.3 - Material gathering recovery

- Fixed a dead-end in the demand-driven material pipeline: coal and supported raw ores could be requested by crafting/smelting recipes, but the generic collector only searched a shallow surface band and then idled without creating a mining route.
- Coal, raw iron, raw copper, raw gold, redstone, lapis lazuli, diamond and emerald requests now enter the cave/mining workflow. If the exact resource is not exposed in an existing cave, the builder can continue its saved stair mine and search the newly exposed tunnel walls for the requested drop.
- Debug labels now display the exact missing material and quantity (for example, `Faltando: coal x1`) while collecting or mining for a material, instead of the generic `Coletando Materiais` label.
- The mine's periodic cave scan now filters for the exact requested ore while a material request is active, avoiding detours to unrelated exposed ores.

### Validation
- Java delimiter checks, resource JSON parsing and ZIP integrity checks pass. A Gradle/Fabric compile and an in-game test are still required; they were not available in this environment.

## 1.2.2 - Builder task labels and natural leaf decay

- Added a visible temporary `[VE]` label above unnamed builder Villagers showing their current task in Portuguese.
- Added `showBuilderTaskLabel` configuration option (default `true`). Existing player-assigned names are preserved.
- Removed all explicit leaf-block harvesting. Builders now harvest trunk logs only and allow vanilla leaf decay to produce sapling drops naturally.
- Sapling drops are collected opportunistically and planted at randomized suitable dirt/grass locations.
- Added configurable `saplingMinimumSpacing` (default 8 blocks) and increased default planting search radius to 32 blocks.
- Sapling replanting is optional and never blocks the construction task when no valid spot or sapling is available.

## 1.2.1 - Tree-only wood gathering and exact structure quantities

### Tree-only wood collection
- The generic material finder no longer accepts arbitrary blocks just because their dropped item matches the requested item. It now mines only an allowlist of natural raw-resource blocks (sand, red sand, gravel, dirt, stone/deepslate, clay, and matching ore blocks). Finished building blocks such as planks, doors, beds, torches, chests and other placed structures are never used as generic raw-material sources.
- Logs remain exclusive to the natural-tree harvesting pipeline. A candidate must pass the natural-root, three-log trunk and canopy checks; vanilla persistent/player-placed leaves are not accepted as natural canopy evidence. Nearby beds, doors, workstations and block entities remain protected.
- Every connected log in the bounded tree volume (including tall trunk sections and branches) is cut one block at a time. Leaves are left intact for vanilla decay and later sapling drops. All collected saplings are replanted where light, soil, spacing and path/house reservations allow; nearby sapling item drops are planted directly if the Villager inventory is full.

### Blueprint-wide quantity planning
- Before gathering a requested block type, the builder counts every still-placeable occurrence of that item remaining in the active house blueprint. It then recursively resolves crafting ingredients for that total instead of requesting one item at a time.
- Existing inventory is credited against the total. The request count is saved with the task, so collection resumes with the same target after a world restart.
- Crafting and smelting recipes are used to create the needed outputs. Recipe batch output can still leave a small unavoidable surplus (for example, door recipes make three doors), and a felled tree yields all of its connected logs; those leftovers are retained for later work.
- Fixed bulk targets such as 88 logs remain disabled. The required amounts come from the selected template and its registered recipe chain.

### Safety and migration
- Pending material quantities are serialized in the active task. Older saves without this field migrate to a one-item request and are recalculated as the blueprint advances.
- Generic material searches cannot dismantle an existing house component to satisfy a missing finished-block item. If the item cannot be crafted or found through a recognized raw-resource source, the task keeps the request and retries rather than harvesting an arbitrary matching block.

### Validation notes
- JSON and source syntax checks are included in the release preparation, but this source has not been compiled against Fabric/Minecraft dependencies or play-tested in-game in the creation environment. GitHub Actions remains the authoritative build check.

## 1.2.0 - Tree-safe harvesting and demand-driven materials

### Tree harvesting and replanting
- Wood gathering now targets verified natural tree roots only. A candidate must be rooted in natural soil, have a three-log vertical trunk and a leaf canopy above it.
- Logs near beds, doors, workstations or block entities are treated as protected wood, reducing the chance of damaging existing houses or player builds.
- The worker fells the selected tree one block at a time, including connected trunk/branch logs, then leaves them intact for vanilla decay; sapling drops are collected later.
- Saplings are replanted on valid soil with suitable light and free space. Replanting avoids the active house footprint and reserved village road; saplings without a suitable location stay in inventory for a later attempt.
- Tree worklists, indexes and log species are persisted, so restarting the world does not restart a partially harvested tree from scratch.

### Exact house material planning
- The selected structure's block placements are counted into an exact item plan. Each block placement requests its corresponding block item and consumes one item only when the block is placed.
- Double-height doors and beds consume one item for their lower/foot half; occupied non-replaceable positions are skipped without consuming inventory.
- Existing inventory is credited first. Missing items are produced through the world's registered crafting recipes, recursively collecting only the ingredients required for the next missing item.
- Added recipe-based wool sourcing from shearable sheep, plus registered smelting-recipe processing for glass and ore-derived ingredients. Smelting is timed and consumes coal/charcoal while preserving logs, planks and sticks for construction recipes.
- Added recovery of crafted/smelted output item entities when the Villager inventory was full at the moment a recipe finished.
- Recipe output batches may produce a small surplus (e.g. a door recipe yields three doors); surplus is retained for later placements or future jobs rather than discarded.
- Material-plan summaries are written to the log when a construction task is assigned.

### Reliability and persistence
- Added persisted plot-tree targets so trees occupying the house footprint are cleared before leveling; workers no longer need to reach a potentially blocked central work position before removing those trees.
- Road obstacles made of leaves and plants are now broken with visible block-breaking progress instead of being deleted instantly.
- Smelting jobs retain their `SMELT_MATERIAL` state when started from the build stage, fixing a state transition that could otherwise interrupt the processing timer.
- Furnace fuel selection no longer burns construction planks, logs or sticks; only coal and charcoal are consumed as smelting fuel.
- Added clay-block sourcing for recipes that need clay balls.
- Legacy saves in the old bulk `GATHER_WOOD` / `GATHER_STONE` stages are migrated back to demand-driven building when they are not midway through an active tree or mine operation.

### Configuration and compatibility
- Added no new required config fields. Existing `replantSaplings`, `saplingPlantRadius`, `materialSearchRadius`, `materialSearchIntervalTicks` and `useRecipeBookForCrafting` settings are used by the new material pipeline.
- Deprecated fixed `woodRequired` and `stoneRequired` stockpile targets remain in the config for migration only; they are forced to zero and are no longer used by new construction tasks.

### Known limitations
- Crafting and smelting use registered recipe data and task timers; they do not operate visible crafting-table or furnace block interfaces.
- Recipes that output multiple items may leave a small surplus because vanilla recipes are crafted in full batches.
- Tree identification is block-shape based. Nearby beds, doors, workstations and block entities are protected, but Minecraft has no universal placed-by-player metadata for arbitrary blocks.
- Full compilation and in-game testing are still required before treating the release as verified.

## 1.1.0 - AI Recovery and Mod Compatibility

### AI and task recovery
- The vanilla Villager Brain now ticks continuously. After each Brain tick, Village Expansion compares the current navigation target to the builder's work target and immediately restores the work route if wandering or another ordinary activity replaced it. This preserves vanilla sensing, memory updates, schedules, and emergency behavior.
- Added navigation progress monitoring, configurable route reassertion, timeout-based repathing and nearby alternate stand positions.
- Added structured warnings when a builder is repeatedly unable to reach a work target.
- Fixed a terrain-leveling deadlock where a builder could retry a fill operation forever without dirt or stone. It now switches to a dirt-gathering stage and resumes the saved operation; if the area has no usable dirt after the configured search limit, it skips the blocked fill rather than freezing.
- Added bounded no-resource search retries. A worker releases a project if it cannot find wood or a safe mine entrance after repeated attempts, allowing a later reassignment rather than waiting indefinitely.
- Added `GATHER_DIRT` as a resumable persisted task stage.

### Third-party mod compatibility
- Added an optional JSON compatibility registry under `config/village_expansion/compat/`.
- Added profession, workstation, biome-style structure, and selection-weight mappings.
- Loads external NBT structure templates through Minecraft's `StructureTemplateManager`.
- Checks that a configured template contains both a bed and the exact configured workstation block.
- Assigns the registered third-party profession and `JOB_SITE` memory after the professional house is completed.
- Keeps the compatibility optional: the third-party mod does not become a hard dependency, and vanilla houses remain as fallback.
- Added `docs/MOD_COMPATIBILITY.md` and an example compatibility file.

### Configuration
- Added `navigationStallTimeoutTicks`, `navigationReassertIntervalTicks`, `navigationMaxRepaths`, and `noResourceSearchLimit` to `config/village_expansion.json`.

### Notes
- Third-party mods must register a Villager profession and workstation block and expose a compatible NBT structure template. The framework cannot infer custom house-generation logic from arbitrary mods automatically.
- This release targets Minecraft 1.21.1 with Fabric. Other Minecraft versions require a separately tested build.
