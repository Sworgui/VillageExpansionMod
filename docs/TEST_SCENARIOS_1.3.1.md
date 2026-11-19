# Regression scenarios — 1.3.1

These are manual in-game acceptance tests. They are documented but were not executed in the packaging environment.

## Recipe graph and tools

1. Start with an unemployed villager, empty inventory, a plains biome with natural trees and exposed stone. Request a house that needs planks, sticks and cobblestone. Confirm that the worker obtains logs, crafts planks/sticks, crafts a wooden pickaxe, mines cobblestone, and resumes the house material goal without repeating completed subtasks.
2. Request iron ingots. Confirm the tree → planks/sticks → crafting table → wooden pickaxe → cobblestone → furnace/fuel → raw iron → iron ingot chain. Confirm no iron ore block is attacked with an insufficient wooden pickaxe.
3. Request an iron pickaxe. Confirm raw iron is mined only after stone-pickaxe access is available; the higher-tier tool is equipped before diamond/emerald requests.
4. Request stripped oak/spruce/birch logs. Confirm the builder crafts/acquires an axe, interacts with the matching natural log to strip it, then harvests the stripped log.

## Ingredient alternatives and pacifist sources

5. Provide two available plank species and a tag-based recipe ingredient. Confirm the planner selects a feasible local variant rather than blindly taking the first tag entry.
6. Request a bed with sheep nearby and no string. Confirm shears are recursively planned/crafted, a live matching-color sheep is sheared, and no spider or other hostile mob is targeted.
7. Request flower-derived ingredients. Confirm the exact requested flower is found, while decorative vegetation in a template remains optional.
8. Request wheat, carrot, potato, beetroot, pumpkin and melon outputs with mature crops present. Confirm only mature matching crops are harvested and unrelated crops are not credited.

## Persistence, cycles and failure handling

9. Save/restart while the villager is gathering a leaf prerequisite. Confirm `pendingMaterialItem` and the parent stack resume the correct parent objective without losing counts.
10. Save/restart while a timed smelting action is pending. Confirm the output and parent stage resume without consuming the same input twice.
11. Use a datapack with two recipes for the same output and alternative ingredient tag entries. Confirm recipe indexing refreshes after datapack reload and the planner chooses a viable route.
12. Add a deliberately cyclic datapack recipe pair or request an item with no supported recipe/source. Confirm recursion terminates at the depth/cycle guard, logs the unsupported resource, and does not claim the item was acquired.
13. Fill most inventory slots with unrelated items. Confirm the builder re-counts needed ingredients and does not consume reserved parent materials accidentally.
14. Request a stonecutting-only output such as a stone brick wall variant. Confirm the builder recursively acquires a stonecutter, input material and any dependencies for crafting the stonecutter before simulating the cut.
15. Put a nearby natural source and an alternate recipe route for the same item in a datapack/test setup. Confirm the direct source is attempted first when estimated cheaper, then recipe fallback becomes available after the configured failed-search threshold.

## Explicit limitations to verify

16. Request an item obtainable only by smithing, brewing, blasting, smoking, campfire cooking, silk-touch drops or a custom modded loot table. In 1.3.1 the planner should report no supported route unless that item also has a crafting, standard furnace smelting or stonecutting recipe/source adapter.
17. Confirm the retained crafting-table, stonecutter and furnace items are inventory prerequisites for simulated recipe processing, not claims that physical workstations were placed or opened.
