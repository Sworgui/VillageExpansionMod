# Test scenarios - Village Expansion 1.2.8

## Clay balls from river/lake deposits

1. Start a world with a river or lake containing clay on the bottom, with a dry shoreline within roughly six blocks.
2. Assign a builder whose active recipe dependency is `clay_ball` (for example, a house material chain requiring clay-derived output).
3. Confirm the villager walks to a dry bank position rather than standing inside the water.
4. Confirm it breaks clay using a shovel and collects clay balls, not unrelated drops.
5. Repeat with clay in a dry exposed patch and confirm the normal surface route still works.

## Dandelion and flower recipe ingredients

1. Use a house/material recipe chain that requires yellow dye or another flower-derived dye.
2. Confirm the debug label may show the selected flower item (for example, `dandelion`) and the builder walks to a matching flower block.
3. Confirm it harvests the exact requested flower item and can resume the crafting/building chain.
4. Save and restart while `dandelion` is pending; confirm the request is preserved and not mistaken for decorative vegetation.
5. Confirm decorative flowers embedded directly in structure palettes are still skipped as optional decoration and are not counted as blueprint output blocks.

## Coal ore and stair-mine vein detection

1. Create a test area without an exposed coal ore target in the initial cave scan and ensure a dry mine entrance can be selected at least 20 blocks from the village centre.
2. Give the builder a recipe chain that requires coal as smelting fuel.
3. Confirm it completes the configured failed cave searches, walks to its registered mine and begins a descending stair tunnel.
4. Place or locate a coal ore vein in a side wall adjacent to a newly dug tunnel segment.
5. Confirm the builder detects that ore after advancing the tunnel, mines it with a suitable pickaxe and collects coal.
6. Confirm the task returns to the original smelting/construction request after enough coal is collected.

## Regression checks

- `grass_block` is still represented by `dirt` in the material planner.
- `short_grass`, tall grass, ferns, dead bushes, vines and snow layers remain optional palette decoration.
- Flower items required by recipes are not discarded by save migration.
- Existing saved mine entrance, mine direction, mine face, and search-failure counters remain intact.
- No test requires deleting the existing world or resetting the builder task.

## Validation status

Static source checks, config/metadata validation and ZIP integrity are performed during packaging. A Gradle/Fabric compilation and in-game simulation must still be run using the repository's GitHub Actions workflow and a Minecraft 1.21.1 test world.
