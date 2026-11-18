# In-game acceptance scenarios — 1.2.6

These are manual Minecraft/Fabric runtime tests. They were not executed by the source-packaging environment.

## Test setup

- Minecraft 1.21.1, Fabric Loader 0.16.14+, Fabric API 0.116.17+1.21.1, Java 21.
- Install the 1.2.6 JAR on both server and client.
- Use a new creative test world or a copied world. Enable the mod and keep the server log visible.
- Spawn adult unemployed villagers using the vanilla Villager Spawn Egg. Do not give them beds or workstations before the test unless a scenario says otherwise.

## Spawn and founding matrix

| Scenario | Spawn | Environment | Expected result |
|---|---:|---|---|
| A | 1 villager | Plains, trees and exposed surface dirt nearby | Villager temporarily becomes Builder, shows the Builder skin and `[VE]` task label, clears/levels a plot, lays a path, gathers materials and builds a house. |
| B | 3 villagers | Plains, one initial spawn point | Builders share a founding project up to the configured builder limit, reserve separate work targets and do not mine the same entrance simultaneously. |
| C | 1 villager | Savanna with sparse trees | Wood search expands after misses; the task remains active and resumes when a natural tree is found. |
| D | 1 villager | Desert or badlands near a biome edge | Builder searches beyond the initial radius for natural trees and materials. If no natural trees exist within loaded/searchable terrain, it waits and retries rather than inventing wood or abandoning the project. |
| E | 1 villager | Taiga or snowy village terrain | Biome-appropriate house template is selected when present; otherwise the plains fallback is used. Builder must still find safe work positions and resources. |
| F | 1 villager | Open terrain with no exposed cave nearby | Builder searches for a safe mine entrance, creates a descending 1×2 stair mine when necessary, and registers the entrance for later reuse. |

## Regression checks

1. **Grass-block materials:** observe a blueprint that contains grass-block placements. The label must request `dirt`, never `grass_block`; an existing 1.2.3 saved task with `pendingMaterialItem=grass_block` must resume as `dirt`.
2. **Material recovery:** temporarily isolate the builder from trees or ore, then restore access within the expanded radius. The house task must remain active and search radius should increase in the log.
3. **Wool and shears:** ensure wool is needed for a bed. The builder should craft shears from recipe ingredients, shear a sheep, damage the shears, and preserve its pickaxe/axe.
4. **Village centre:** after the founding professional house, the builder must obtain one gold block, place the gold pedestal and bell, then spend the gold block. The village must not leave founding state before the bell and all loaded villagers' valid beds are registered.
5. **Blocked construction cell:** place a villager or passive mob in the next blueprint cell. The builder must wait and retry without placing a block through the entity. Once the cell clears, construction should continue.
6. **Navigation and safety:** test a villager in a shallow pit, near water, on fire, and at critically low health. It must not keep mining/building through a safety emergency; after recovery it should resume the saved stage.
7. **Home recovery:** break a villager's remembered bed while its chunk is loaded. Its stale HOME memory should be cleared, and a homeless unemployed villager should become eligible to build a replacement home.

## Evidence to collect

For each scenario, save the server log from villager spawn until the first house is complete. Also capture the villager task label at each stage and note whether the builder was paused, searching at an expanded radius, or switched to a mine. Record any case where a task label remains unchanged for more than five in-game minutes while the villager is not navigating, breaking a block, sleeping, fleeing or waiting for an occupied placement cell.

## Regression checks for 1.2.6

- **Stone to cobblestone:** start a house whose blueprint needs cobblestone; place a villager next to an exposed stone face and verify it equips a pickaxe, breaks the stone, receives cobblestone, and resumes construction.
- **Cave wall / mined tunnel:** repeat with stone only inside a cave, then in flat terrain with no cave. Verify the miner creates/reuses its saved stair mine, reaches stone and receives cobblestone from blocks it clears.
- **Ore drops:** request raw iron, coal, copper and gold in separate test worlds. Verify each block's loot table receives the villager's held pickaxe and the expected raw/ore item enters inventory.
- **Inventory filtering:** add loose dirt, gravel and granite drops around the mine face while the villager needs cobblestone; confirm unrelated drops are left on the ground and do not fill the inventory.
- **Existing world migration:** load a world saved with 1.2.4 while a builder task is waiting for cobblestone; confirm it continues without resetting the village or task.


## Regression checks for delayed stair-mine fallback (1.2.6)

8. **Five failed stone searches:** create a flat test area with no exposed cave/stone face inside the initial search area, but with stone underground. Request cobblestone. Confirm the task label remains on stone collection for the first four completed unsuccessful scans; only after the fifth unsuccessful scan does the villager switch to `Escavando mina`. A scan is one completed resource search, not one game tick.
9. **Mine offset from village:** after the fifth miss, record the registered mine entrance and the village centre. Its horizontal X/Z distance must be at least 20 blocks. The entrance should be clear, dry, unoccupied, and the villager should walk to it before digging a descending 1x2 staircase.
10. **Existing mine too close:** preload/register a test mine less than 20 horizontal blocks from the village centre. Confirm the builder does not reuse it and chooses a new entrance at or beyond the configured minimum. A valid existing mine at least 20 blocks away may be reused unless reserved by another active builder.
11. **Counter persistence:** let the builder complete two or three unsuccessful stone scans, save and restart the world, then continue. Confirm it resumes at the saved stone-search count and begins its own mine only when the fifth unsuccessful scan is reached. The project must not jump to BUILD merely because no current stone target was saved.
12. **No suitable entrance yet:** block/disable the nearby candidate sites or keep their chunks unloaded. Confirm the builder retains its gathering project, waits between attempts and expands the search range rather than standing idle or abandoning the house.

## 1.2.6 configuration defaults

```json
{
  "stoneSearchFailuresBeforeMine": 5,
  "mineMinimumDistanceFromVillage": 20
}
```

`mineMinimumDistanceFromVillage` has a hard lower clamp of 20 blocks. Distance is measured horizontally from the stored village centre (X/Z); if no village record is available, the house site is used as the fallback anchor.
