## 1.4.12

- Removed the JUnit test's direct `Bootstrap.initialize()` call, which raised `IllegalAccessError` under the Fabric Loom remapped test runtime.
- Extracted vanilla tool-family and harvest-tier compatibility into `MaterialToolRules`, a registry-independent pure Java helper. Runtime planning converts registered Minecraft items to identifiers and delegates to the same rule implementation.
- Reworked `MaterialToolCompatibilityTest` to use vanilla item identifiers rather than initializing `Items` and Minecraft registries. This preserves coverage for shovel upgrades, tool-family separation, golden harvest tiers, diamond ore and obsidian thresholds.
- Corrected the GitHub Actions artifact path to the current versioned JAR name (`village-expansion-1.4.12.jar`).

## 1.4.10

- Fixed the server-world variable passed to `hideBreakingProgress()` after the vanilla Brain tick.
- Added the missing `BuilderTask.smeltWorkstationPos` field; the existing NBT serialization/restoration and smelting workflow now refer to a declared task property.
- Updated the CI artifact name for 1.4.10.

## 1.4.9

- Fixed the Fabric compilation error in `MaterialAcquisitionPlanner`: imported `net.minecraft.block.Block`, which is used by the nearby-workstation lookup helper. This is a compile-only fix; runtime behavior is unchanged from 1.4.8.
- Bumped the mod version and CI artifact name to 1.4.9.

## 1.4.8

- Corrected tree mining interruptions: leaves are transparent to the working ray, while non-leaf colliders cause a side change rather than a counter reset. If navigation to one side fails (including after knockback or entity pushing), the builder rejects only that stand and retries the same log from another side instead of skipping it immediately.
- Nearest-tree selection retains its complete local six-block scan and distance ranking; the acceptance checks now explicitly cover a tree beside the worker versus a farther tree.
- Preserved the profession texture's original alpha mask (2,648 transparent pixels) and recolored only visible pixels. This avoids opaque black pixels in transparent UV areas and replaces the yellow hardhat/orange workwear with a slate-blue hardhat, teal workwear and leather tool trim.
- Temporary crafting tables now remain placed for a 20-tick use window and are reclaimed after the recipe completes; their position and pending output are saved so a reload does not leave the builder searching or lose ownership. Temporary furnaces remain present and lit throughout the simulated cooking interval, then are reclaimed.
- Corrected tool harvest-tier ordering: golden tools are fast but have wooden harvest tier. A golden shovel/axe can satisfy a wooden-tool task, but cannot replace stone/iron when a higher harvest tier is required. Stone shovels continue to satisfy wooden-shovel goals, including when held in the offhand or inventory.
- Added regression assertions for golden-versus-stone tool compatibility and expanded the server acceptance matrix to 21 scenarios, including leaf-transparent mining, failed navigation-side retries, saved temporary workstations and texture alpha integrity.
- Bumped the mod version and CI artifact to 1.4.8.
- A negative professional-supplier lookup now yields to ordinary acquisition planning on the next tick; discovering an already-near workstation invalidates stale plans within at most 10 ticks.

## 1.4.7

- Fixed a legacy biome-migration hole: saved `currentTreeLogItem` is now remapped too. If a task was already marked `plains` but retained an in-flight `spruce_log` tree scan, that stale scan is discarded and the pending goal continues with oak-family wood.
- Tool capability checks now include the offhand. A stone shovel satisfies a wooden-shovel request, and an offhand upgrade is promoted to the main hand without deleting the previous item. Minimum harvest requirements remain enforced end-to-end: diamond ore requires iron-tier pickaxe; obsidian is now a supported mining target and requires diamond-tier.
- Nearby crafting tables and furnaces are detected within a six-block horizontal / three-block vertical work radius. A completed material plan is refreshed when the villager reaches a previously out-of-range workstation, even if the old plan was technically viable; crafting-table and furnace context are refreshed independently.
- Builders can place a temporary crafting table or lit furnace from their inventory on a safe, unoccupied solid floor, run the existing recipe/cooking pipeline (with smoke/flame emitted at the furnace), then remove the temporary block and recover the item. Existing world workstations are never removed. Overflow return items are dropped safely instead of deleted.
- Tree lookup first fully scans a six-block local neighborhood and ranks candidates by reachable work-stand distance. Alternative stands around foliage/colliders preserve accumulated block-breaking progress; knockback pauses rather than resets the same block's mining session.
- Updated the normal and zombie builder profession textures to a slate-blue hardhat, muted teal workwear and brown tool-belt palette; the 64x64 texture atlas layout is retained.
- Added regression assertions for shovel/pickaxe tier compatibility and a 15-case in-game acceptance matrix covering stale biome saves, nearby trees, foliage/knockback mining, workstation reuse and texture loading.
- Bumped the mod version and CI artifact to 1.4.7.

## 1.4.6

- Wood-material normalization is now keyed to the final house-site biome and reruns whenever that site biome changes. Legacy/stale `spruce_log` requests are remapped to oak-family goals for Plains/Oak/regular Forest plots; the debug label and server log identify both the house-site biome and the worker's current biome so a border-site mismatch is visible.
- Mining sessions now survive block-state property changes for the same block at the same position. If knockback moves the worker away, mining pauses outside a four-block horizontal work radius and resumes on return instead of discarding accumulated progress.
- A failed route to one side of a tree no longer skips that log or clears the mining counter. The worker rejects the failed stand, searches up to four blocks around the same log, and retries another side. Occupied stand/head cells are excluded.
- Tree selection is ranked by distance to the actual accessible work stand, not only the trunk's 3D coordinate, reducing trips to a farther tree when a nearer tree has a reachable work position.
- Added a new slate-blue hardhat, muted teal workwear and brown tool-belt palette for both normal and zombie builder profession overlays. The 64x64 atlas layout and transparency are preserved.
- Added unit coverage for Plains -> oak mapping and mining-session retention/pause rules, plus an eight-case server acceptance matrix for knockback, foliage, alternative stands, tree distance and texture loading.
- Bumped the mod version to 1.4.6 and updated the CI artifact name.

## 1.4.5

- Fixed the idle "missing oak_log/spruce_log" state: exact raw-log objectives now bypass the recipe/provider planner and immediately enter natural-tree acquisition. Raw logs can be harvested by hand, so the first tree is no longer gated on crafting an axe or waiting for an unrelated recipe plan.
- House palettes are now reloaded after the final plot is selected, using the plot's biome instead of the village-center biome. Restored task material goals (including deferred recipe ingredients) are migrated to that same local wood family.
- Replaced sparse tree-column sampling with a center-out square spiral processed in bounded 1,024-column slices. Consecutive retries continue where the previous slice stopped instead of sampling the same gaps; the scan still filters by the exact log species required by the blueprint.
- Fixed the retry timer so a failed search does not restart every task tick. A new exact-log objective resets it once; later scans resume after the retry interval and continue from the next spiral slice.
- Updated labels to distinguish searching for a matching tree from walking to a selected tree.
- Added regression coverage for complete spiral coverage, no duplicate columns and correct continuation between slices, plus eight focused in-game acceptance scenarios for Oak Forest wood selection, saved-task migration and reliable log discovery.
- Bumped the mod version to 1.4.5 and updated the CI artifact name.

## 1.4.4

- Fixed a circular material dependency that prevented builders from collecting exact blueprint log items: raw logs can now be harvested by hand when no axe is available, and the worker no longer tries to craft the axe from the same logs it cannot collect.
- Tool selection no longer clears an unrelated main-hand item when no tool from the requested family exists.
- Replaced the 24-column random tree lookup with a deterministic, phase-shifted grid search. The scan retries over an expanding radius, checks the exact log species required by the blueprint, and logs the search radius when no matching natural tree is found.
- Added podzol, mycelium, moss, rooted dirt and mud to natural surface detection so trees growing on those floors can be found.
- Added biome-native wood adaptation for vanilla village templates: oak/regular forest, birch forest, dark forest, taiga/snow, jungle, savanna, mangrove, cherry and pale garden templates use the matching local wood family. Block-state properties such as facing, half, axis and waterlogging are preserved during conversion.
- Added pure JUnit coverage for biome wood selection, block-id remapping and deterministic tree-search coverage.
- Bumped the mod version to 1.4.4.

## 1.4.3

- Fixed a movement regression in 1.4.2: the Brain hook no longer stops navigation whenever the custom task has a transient null `moveTarget` during planning or stage transitions.
- Throttled navigation arbitration to once every 20 ticks. Reissuing `startMovingTo()` every entity tick could continuously rebuild a valid path and prevent the villager from visibly moving.
- Kept profession enforcement and active block-breaking progress on every entity tick; only route arbitration is throttled.
- Added `BuilderNavigationPolicy` and JUnit regression coverage for arbitration cadence and timed route reassertion.
- Expanded the in-game acceptance matrix to 42 scenarios, including navigation continuity through planner transitions.
- Bumped the mod version to 1.4.3 and updated the GitHub Actions artifact name.

# Changelog

## 1.4.2

- Reworked block mining to advance once per game tick and derive break duration from block hardness, the held tool's mining-speed multiplier and vanilla suitable/unsuitable harvest divisors.
- Tool durability is applied once per completed block. A broken axe, pickaxe or shovel is detected before the next target; replacement acquisition is routed through the existing material/recipe planner before mining resumes.
- Fixed the exact-ore recovery order: the required pickaxe is acquired before suitability filtering, and the original ore/material goal is preserved while the tool is planned.
- Added explicit recovery actions for every builder stage when navigation cannot reach its current target; failed resource blocks, tree roots, mine entrances and construction stands are excluded from subsequent searches. Tool swaps preserve the previously held tool instead of overwriting it.
- Construction now tests alternate work stands while filtering occupied cells. Unreachable blueprint positions, living-entity occupancy and repeated placement rejection have bounded retry windows, after which only the obstructed placement is skipped. Inaccessible individual logs in a connected tree are skipped without abandoning the rest of the tree.
- Reasserted builder navigation after every vanilla Brain tick and clear vanilla WALK_TARGET/job-site memories while a construction task is active. The temporary builder profession is restored if a vanilla task attempts to assign a workstation profession.
- Increased default custom-task cadence from a 40-tick minimum to 10 ticks, with one global round-robin slice per tick; actual mining progress is independent and advances every tick.
- Added JUnit regression tests for mining-time math and exhaustive stage-recovery mapping, plus a 41-scenario in-game acceptance matrix covering tools, AI overlap, navigation, occupied placements, multiple builders and founding projects.
- Added config migration version 8 and bumped the mod version to 1.4.2. GitHub Actions now runs the JUnit suite before building and uploads the 1.4.2 artifact.

## 1.4.1

- Fixed route-wide pre-clearing: new builders no longer walk away from the plot to fell trees along the future road before constructing the house.
- Reordered the project to clear/level only the house footprint, build the house, then build the access road. Trees on the road are handled only when an actual road block is obstructed.
- Migrated saved `CLEAR_PATH` tasks from 1.4.0 to local `LEVEL_SITE` preparation and discarded their old route-wide tree targets.
- Reduced default house-site search to a 4–12 block radius with 2-block bands.
- Increased adaptive terrain allowance to 6 blocks from the median floor and 160 operations by default; natural dirt/stone is cut and reused as fill where possible.
- Added a local work-position fallback beside the next cut/fill operation when perimeter positions are blocked.
- Road tree handling now abandons an unreachable local obstruction instead of repeatedly pursuing a distant work position.
- Added config migration version 7 for the compact site radius and expanded bounded terraforming defaults.

## 1.4.0

- Reduced default house-site search to a compact 4–18 block radius around the village.
- Replaced the strict naturally-flat-site requirement with bounded adaptive terrain preparation: default maximum 4-block height variation and 96 cut/fill operations.
- Kept protection for water, lava, block entities and existing solid/player-built structures; only the footprint's natural terrain and vegetation are eligible for clearing/terraforming.
- Added perimeter work-stand selection for leveling. Builders no longer navigate to a fixed northwest-corner coordinate that may be floating, underground or blocked on uneven plots.
- Flat/ready plots skip LEVEL_SITE navigation and proceed directly to BUILD.
- Expanded tree-cutting stand search to radius two and made inaccessible individual logs non-fatal to the house project.
- Added a terminal navigation-failure marker so callers can reject an unreachable target and select another local fallback instead of issuing the same route indefinitely.
- Added config migration version 6 for compact site search and terraform limits.

## 1.3.9

- Added an ultra-low-CPU profile with a 2-tick controller cadence, one global custom builder task slice every 2 ticks, and a 40-tick minimum interval per builder. With 20 active builders, each worker receives roughly one custom work slice every 2 seconds while vanilla Brain/navigation continues at normal tick rate.
- Added a hard dimension-wide cap of 20 active builder projects, including saved tasks in unloaded chunks; the existing per-village cap remains in effect.
- Reduced assignment cadence to once per 40 ticks, spatial-search permits to once per 20 ticks, lazy recipe-plan advancement to once per 40 ticks, and recipe-index batches to 2 entries per tick.
- Reduced navigation destination reassertion to once per 40 ticks and capped road A* expansion at 300 nodes.
- Cut tree-canopy checks from 1,053 block probes to 175 sparse probes, protected-building scans from 1,859 to 392 positions, and route-tree sampling from about 48 to 24 anchors.
- Reduced exposed-cave samples and exact ore vein checks; repeated local checks occur as the miner advances rather than scanning a large 3D volume in one task slice.
- Added a bounded A* road path implementation using the vanilla heightmap and loaded-chunk checks.
- Added performance migration version 5 so existing configs receive the new conservative defaults automatically.

## 1.3.8

- Added a strict round-robin work budget: at most one expensive custom builder task slice is advanced per world tick, with each individual builder updated no more often than every 20 ticks by default. Vanilla Brain, navigation and movement remain enabled every game tick.
- Added Fabric `ServerEntityEvents` load/unload callbacks to maintain the loaded-villager cache and restore at most one saved builder task per tick. Removed the builder controller's periodic full-world entity scan.
- Changed the breeding fallback to use the controller's cached villager list rather than iterating every entity in the world.
- Added an adaptive low-priority budget: recipe-planning and founding-completion work is deferred when the current controller tick has already consumed its 2 ms soft budget.
- Bounded direct resource scans to small random samples with heightmap-assisted surface lookup; future retries expand the search radius, rather than scanning the entire square at once.
- Bounded wood, stripped-log, dirt, clay, direct-material and mine-entrance searches; reduced repeated terrain and standing-position queries.
- Reduced path-tree discovery to capped route samples and bounded path-surface lookups.
- Kept plot clearing, tree-log discovery, tree cutting, blueprint material counting and sapling planting incremental/cached.
- Reduced house-site candidate evaluation to two candidate footprints per assignment attempt and moved assignment attempts to a 10-tick interval.
- Added 10 ms diagnostics for builder task, assignment and controller slices.
- Added a version-4 config migration for the event-driven loaded-villager cache and low-CPU assignment cadence.

## 1.3.7

- Introduced resumable Lazy Planning: material objectives are evaluated in separate direct-source, crafting, stonecutting and smelting phases spread across controller ticks.
- Added a global round-robin planning scheduler that advances at most one builder's planning slice per world/controller tick, preventing multiple builders from launching recursive recipe searches simultaneously.
- Built recipe indexes by retaining a lazy iterator over `RecipeManager.keys()` and resolving only a small batch of entries per tick. The planner no longer calls `listAllOfType()` or materializes three full filtered recipe lists in one tick.
- Reduced the recursive planner's per-slice evaluation and world-source scan budgets; execution reuses the selected route/recipe rather than repeating cost estimation.
- Kept completed parent plans in memory while child dependencies are acquired; a world reload safely rebuilds transient recipe choices from the persisted material goal.
- Supplier lookup and recipe-index batches continue on successive ticks; expensive material-comparison phases remain separated by the configurable 4-tick default interval. This keeps first-time index creation responsive without recalculating a full plan every tick.

## 1.3.6

- Fixed a server freeze pattern during material acquisition: the recursive recipe/provider planner was being reevaluated before the material retry cooldown, even when the builder was already moving toward a selected source.
- Cached the builder's selected direct-resource target and reused it until it is collected or becomes invalid; the same large surface scan is no longer repeated every server tick while walking to one block.
- Replaced full-height column scans inside broad direct-resource searches with a heightmap-based, bounded-depth surface lookup. This avoids scanning hundreds of Y levels for every X/Z column.
- Added a planner retry gate: expensive recipe/provider evaluation runs only when the goal is newly due, when its requested item has become available, or when no active work target exists and its retry window has expired.
- Replaced eager recursive log/stone quota estimation during builder assignment with a count of literal blueprint materials. Exact recipe dependencies remain handled lazily when each block/item is actually required.
- Added a shared per-decision planner budget (96 evaluation steps, 3 world-source scans), bounded recipe alternatives, and a capped 12-item ingredient candidate set to prevent modded tags from causing combinatorial search.
- Prioritized ingredient variants already in the builder inventory, preferred-tag alternatives, and directly executable natural sources before considering a bounded fallback set.
- Added a conservative non-recursive fallback cost when the planner budget is exhausted.
- Kept vanilla Brain idle/look behavior active while no construction destination exists. The builder cap still limits active construction jobs only; it does not cancel spawn-egg entity spawning.
- Added regression scenarios for first-builder responsiveness, repeated direct-block searches, many-alternative recipes, multiple spawn eggs in separate positions, and planner retry cooldowns.


## 1.3.5

- Fixed a server crash during builder assignment: the tagged-material cost estimator previously selected recipe ingredients with a null Villager context, which reached `MaterialAcquisitionPlanner.countInventory()` and threw a NullPointerException.
- Threaded the actual builder entity through tagged-material estimates and ingredient selection so inventory, equipped tools and nearby professional suppliers are evaluated against the correct worker.
- Ingredient choice now evaluates the amount required by the recipe batch, instead of always estimating one ingredient item.
- Added defensive null guards to planner entry points and inventory/tool checks to prevent invalid contexts from crashing the world tick.
- Added regression scenarios for assignment-time tag estimation, alternative ingredients and invalid null contexts.


## 1.3.4

- Fixed the GitHub Actions compile failure in the 1.3.3 professional supplier feature: declared all supplier visit/rejection state fields on `BuilderTask`.
- Kept provider UUID, visit start time, rejected provider/item and rejection timeout in the persistent task model, matching the existing NBT write/read logic.
- Updated workflow artifact name and installation instructions to 1.3.4.

## 1.3.3

- Hardened free acquisition from employed Villagers: builders can collect multiple output stacks from one matching offer in a single visit, reserving one available use for each stack.
- Kept the search generic across all non-nitwit professions; the output item, not a hard-coded profession name, determines whether a supplier is suitable.
- Preserved the 40-block default search radius and config toggle.
- Added explicit regression cases for repeated stock output, exhausted offers, radius limits, fallback and save/reload visits.
- Updated build artifact and installation instructions to 1.3.3.


## 1.3.2

- Added free professional resource sharing to the recursive material planner.
- Builders scan a configurable radius (40 blocks by default) for employed villagers with a currently available trade offer that outputs the requested item.
- Builders walk to the nearest matching professional and receive the output without paying emeralds, barter inputs or granting merchant trade XP.
- One use is reserved on the matching offer, preserving finite stock and allowing normal villager restocking to replenish it.
- Exact items and equal-or-better tools of the same tool family can satisfy a material goal.
- Provider UUID and visit start time are persisted so a save/reload resumes the supplier visit.
- Failed or unavailable provider visits return to the normal recursive natural-source/crafting/stonecutting/smelting planner.
- Replaced the previous blacksmith upgrade that consumed three builder-owned iron ingots. The legacy config key remains readable for compatibility.

## 1.3.1

- Added recursive material planning across crafting, stonecutting and smelting recipes.
- Added peaceful/natural source evaluation, tool prerequisites and persistent dependency goals.
