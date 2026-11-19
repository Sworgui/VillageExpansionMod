# Regression test scenarios — 1.3.0

Run in Minecraft 1.21.1 with a clean test world and again with a copy of a 1.2.8 save. Enable `showBuilderTaskLabel` and inspect the server log.

## Dependency planning
1. Spawn one unemployed adult villager on plains with a natural oak tree and exposed stone nearby.
2. Confirm the builder gathers logs, crafts planks/sticks, retains a crafting table, then obtains a wooden pickaxe before mining coal/stone.
3. Force a blueprint/material request for raw iron. Confirm the villager first obtains cobblestone and sticks, crafts a stone pickaxe, equips it, then mines iron ore.
4. Force a diamond/emerald request. Confirm it does not repeatedly target the ore with a stone pickaxe; it obtains an iron pickaxe first.
5. Force a stripped-log placement. Confirm the villager finds the matching natural tree species, crafts/obtains an axe, strips the log, and collects the stripped item.
6. Force a white-wool request with sheep nearby and no string. Confirm the villager crafts shears and shears sheep; it must not seek spiders or hostile drops for bed wool.
7. Force a recipe dependency that needs multiple subrecipes. Confirm the parent item and exact quantity are restored after each child ingredient is gathered.

## Mining worksite discipline
8. Put exposed stone in a ravine. Confirm the villager selects a quarry centre and mines within its bounded local area rather than leaving isolated holes across the ravine.
9. Exhaust that quarry. Confirm it clears the exhausted centre before selecting another source.
10. Reload the world mid-quarry. Confirm the same quarry centre is resumed.
11. Provide a coal vein behind stone. Confirm the builder equips a suitable pickaxe and mines the coal vein rather than repeatedly attempting an unsuitable tool.

## Navigation and construction
12. Create a two-block ledge or shallow pit between the villager and its work target. Confirm it tries alternate stands/repathing and, when safe and supplied, places one dirt/cobblestone step without intersecting entities.
13. Place another villager or passive mob in the proposed rescue cell. Confirm no block is placed through the entity.
14. Give the site already-flat terrain. Confirm level operations already satisfied by the world are skipped and the builder advances.
15. Start a house on an existing 1.2.8 task saved in `BUILD_PATH`. Confirm the unfinished house resumes before the road.
16. Confirm the house is completed before a three-block-wide path is built from the village path network toward the house entrance.
17. Place a mob in a blueprint block position. Confirm the builder waits instead of placing a block inside the entity.
18. Attack the villager or ignite it. Confirm danger/health pause takes precedence over all planning and building subtasks.

## Save compatibility
19. Reload while the builder is gathering a recipe ingredient, mining an ore, stripping a log, building the house, and building the road. Confirm each phase resumes from saved indices and pending/deferred material state.

## Expected limitations
- Crafting and smelting remain recipe-driven simulations; villagers do not operate the physical crafting-table/furnace GUI.
- The rescue step is a single-block recovery action, not unrestricted bridging or climbing.
- Three-wide roads follow the existing A* centreline; unsafe or invalid side cells can be omitted.
- These are manual in-game scenarios. Static source checks do not prove gameplay success.
