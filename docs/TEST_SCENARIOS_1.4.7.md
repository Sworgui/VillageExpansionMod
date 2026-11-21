# Village Expansion 1.4.7 — Server acceptance scenarios

These scenarios require a Fabric 1.21.1 world. They are documented acceptance checks; they are not claimed as executed by the local source-packaging validation.

| # | Setup | Expected behavior |
|---:|---|---|
| 1 | Restore a saved Plains house task whose pending material is `spruce_log` and whose saved biome marker already says `plains`. | The pending goal and any in-flight tree-log species are normalized to the oak family; the stale spruce tree scan is discarded. |
| 2 | Create a new house site in Plains while the villager stands near a different biome. | House palette follows the final plot biome, not the worker's current biome. |
| 3 | Place a matching tree within six blocks and another matching tree farther away. | The local search selects the nearest reachable work stand first. |
| 4 | Put leaves or another collider between the villager and a selected tree trunk. | The villager changes to another reachable side; accumulated work for the same trunk is not reset. |
| 5 | Apply knockback during tree mining, then let the villager return. | Mining pauses while out of reach and resumes with the existing progress. |
| 6 | Put a stone shovel in inventory or offhand while the task asks for a wooden shovel. | Stone shovel is equipped/accepted; no wooden shovel request is created. |
| 7 | Put a stone pickaxe in inventory while diamond ore is required. | Stone pickaxe is rejected for that objective; the planner asks for at least iron. |
| 8 | Put an iron pickaxe in inventory while obsidian is required. | Iron pickaxe is rejected; obsidian is routed to the mining stage and the planner requires diamond or netherite. |
| 9 | Place a crafting table one block from the villager; provide no crafting-table item. | A crafting recipe requiring a table uses the existing block; the villager does not seek another table. |
| 10 | Place a furnace one block from the villager; provide no furnace item. | A smelting recipe uses the existing furnace; the villager does not seek another furnace. |
| 11 | Remove nearby crafting tables/furnaces but provide the corresponding item. | The villager places a temporary workstation on a safe solid floor, uses it, removes it and returns the item to inventory. |
| 12 | Start a failed material plan, then move the villager into range of an existing workstation. | The stale failed plan is invalidated once and recalculated with the new workstation context. |
| 13 | Fill the villager's inventory before a temporary workstation is returned. | The workstation item is dropped safely at the villager instead of being deleted. |
| 14 | Save/reload during a temporary-furnace smelting task. | The temporary furnace appears lit, smoke/flame particles are emitted at its position, and the saved furnace position is cleaned up after the task completes or is abandoned; the furnace item is returned once. |
| 15 | Build and inspect both normal and zombie builder variants. | Both use the slate-blue hardhat, muted teal workwear and brown tool-belt texture, with the original 64x64 UV layout. |

## Automated coverage

`MaterialToolCompatibilityTest` contains pure tests for same-family tool tiers, provider compatibility, diamond-ore and obsidian minimum pickaxe tiers. `BiomeWoodProfileTest` covers Plains-to-oak mapping. The local environment used for packaging does not include a runnable Gradle wrapper/installation or Minecraft/Fabric runtime, so a full JUnit execution and live-server run remain pending in CI or a development setup.
