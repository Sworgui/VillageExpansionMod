# General material acquisition planner — 1.3.1

## Contract

The house blueprint requests an output item and quantity. The planner resolves that goal against the server's live recipe data and a whitelist of acquisition methods that the builder executor can actually perform. It returns a viable recipe/source estimate rather than assuming every item is obtainable.

## Resolution order

1. Count the requested item in the villager inventory. Existing inventory is always preferred. Tool-family checks may satisfy a lower-tier tool requirement with an equal or better tool already held.
2. Evaluate direct supported sources for the exact requested item. Examples include logs from verified natural trees, stripped logs after axe interaction, cobblestone/ore drops from supported stone blocks, clay balls from clay deposits, exact flower blocks, mature common crops, wool from live sheep, and safe terrain blocks.
3. Compare the estimated cost of direct acquisition with processing. If a supported natural/peaceful source is cheaper, the controller tries to collect it first. After repeated failed source searches, the controller may fall back to an available recipe path.
4. Evaluate every registered crafting recipe whose output is the requested item. Respect output batch size, compare possible recipe alternatives, then compare ingredient alternatives (including item tags) using estimated source/recipe cost.
5. Evaluate registered stonecutting recipes and include the stonecutter item plus selected input as dependencies.
6. Evaluate registered standard furnace-smelting recipes and include input, a retained furnace item, and coal/charcoal fuel in the dependency estimate.
7. Reject recursion cycles and chains deeper than 12 dependencies. If all routes are impossible under the supported rules, report that no safe acquisition plan is known and keep the house task intact for later retry/compatibility handling.

## Executing a dependency tree

The controller does not execute the whole tree at once. It stores the current leaf in `pendingMaterialItem`/`pendingMaterialCount` and stores parent goals in the persisted `materialGoalStack`. It resolves the deepest missing prerequisite first, then resumes its parent. Dependency ordering is preserved as a `LinkedHashMap` so it is not lost when the plan crosses the planner/controller boundary. Inventory is re-counted before every craft and every parent resume.

Example: an iron pickaxe request can expand into iron ingots + sticks; iron ingots can expand into raw iron + furnace + fuel; raw iron can expand into stone-pickaxe access; stone-pickaxe access can expand into cobblestone + wooden pickaxe; tools then expand into crafting table, planks and logs. A wool request expands into shears, then iron ingots and their own requirements, rather than looking for string from hostile mobs.

## Recipe/workstation behavior

- A retained crafting-table item is required for tool recipes and recipes with more than four non-empty ingredient slots. The crafting table itself can be made from planks without another workstation.
- Stonecutting is simulated from the registered recipe data. A stonecutter item is included as a dependency, but the villager does not physically place or open a stonecutter GUI.
- Standard smelting is a timed simulation and consumes input and coal/charcoal fuel. A furnace item is included as a dependency, but the villager does not physically place or open a furnace GUI.
- Crafting is also simulated from the registered recipe data; the villager does not physically open the 2×2 or 3×3 crafting UI.
- Batch outputs are retained in inventory/nearby drops; the controller rechecks counts so the same material is not needlessly reacquired.

## Why source providers are explicit

Recipes alone do not tell an NPC how to find a world source or what is safe to harvest. Direct acquisition therefore uses an explicit adapter list. A block item with a custom loot table, a silk-touch-only block, a hostile mob drop, or a third-party resource with a bespoke interaction is not treated as obtainable merely because an item or block has a similar name. Such resources need a deliberate source adapter. This prevents the planner from making promises the executor cannot fulfill.

## Supported in this version

- Registered crafting recipes (including datapack crafting recipes).
- Registered standard furnace smelting recipes.
- Registered stonecutting recipes.
- Verified natural trees/logs and axe-stripped logs.
- Supported stone, cobblestone and ore drops with pickaxe-tier checks.
- Clay deposits, exact flowers, mature wheat/carrots/potatoes/beetroots, pumpkin/melon blocks, sheep wool and selected safe terrain blocks.

## Not supported as general acquisition routes yet

Smithing, brewing, blasting, smoking, campfire cooking, villager/player trading, hostile mob drops, arbitrary third-party loot tables, silk-touch-only block items and automatic farm creation/maintenance are not generic providers in 1.3.1. Some of these outputs may still be obtained if they also have a supported crafting or standard smelting recipe.


## 1.3.2 professional suppliers

The acquisition planner now considers available output offers from employed villagers as a source for any exact requested item, plus equal-or-better tools of the same family. The controller visits the nearest matching professional within the configured radius (40 blocks by default) and copies the offered output for free. It does not consume the offer's buy stacks or call the vanilla merchant trade method. Instead it advances the offer use counter once per output grant to preserve finite supply and vanilla restocking. Provider UUID and visit start time are saved in the active construction task.


## 1.3.3 supplier transfer refinement

The 1.3.2 free-professional provider path is retained. In 1.3.3, a single matching offer can provide more than one output stack during one supplier visit while uses remain. Each stack delivery advances the offer use counter once; the buyer's currency/barter inputs and the merchant XP path remain unused. Matching remains output-driven and accepts better tools only in the requested tool family.
