# In-game acceptance scenarios — 1.2.4

These are manual Minecraft/Fabric runtime tests. They were not executed by the source-packaging environment.

## Test setup

- Minecraft 1.21.1, Fabric Loader 0.16.14+, Fabric API 0.116.17+1.21.1, Java 21.
- Install the 1.2.4 JAR on both server and client.
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
