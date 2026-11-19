# Changelog

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
