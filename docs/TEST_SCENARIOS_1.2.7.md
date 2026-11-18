# Test scenarios - Village Expansion 1.2.7

## Decorative vegetation in structure templates

1. Start a new world in plains, savanna, desert, taiga and snowy biomes.
2. Spawn an unemployed villager with a spawn egg and let it become a builder.
3. Check the selected vanilla house template and the startup material-plan log.
4. Confirm that no plan entry is generated for `short_grass`, `tall_grass`, `fern`, `large_fern`, `dead_bush`, flower blocks, vines or snow layers.
5. Confirm the builder progresses through material gathering and construction without waiting for a plant item.

## Save migration

1. On a pre-1.2.7 save, create or load a villager whose debug label shows `Faltando: short_grass`.
2. Stop the server, install 1.2.7 and restart the same save.
3. Confirm the log reports that the stale decorative material request was cleared.
4. Confirm the villager returns to the saved build stage and skips the plant placement when it reaches that blueprint entry.
5. Confirm normal requests for logs, cobblestone, iron, wool, glass, doors, beds and workstations still work.

## Regression checks

- Grass blocks in a blueprint must still be planned as dirt, not `grass_block`.
- Decorative plant blocks already present in the world must not be harvested to satisfy a building-material request.
- A template without decorative vegetation must retain its normal placement order and material counts.
