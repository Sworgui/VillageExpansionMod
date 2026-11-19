# Changelog

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
