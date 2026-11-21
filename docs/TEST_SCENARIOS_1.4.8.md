# Village Expansion 1.4.8 — Server acceptance scenarios

These scenarios require a Fabric 1.21.1 world. They are documented acceptance checks; they are not claimed as executed by the local source-packaging validation.

| # | Setup | Expected behavior |
|---:|---|---|
| 1 | Restore a saved Plains house task whose pending material is `spruce_log` and whose saved biome marker already says `plains`. | The pending goal and any in-flight tree-log species are normalized to the oak family; the stale spruce tree scan is discarded. |
| 2 | Create a new house site in Plains while the villager stands near a different biome. | House palette follows the final plot biome, not the worker's current biome. |
| 3 | Place a matching tree within six blocks and another matching tree farther away. | The local search selects the nearest reachable work stand first. |
| 4 | Put multiple leaf blocks between the villager and a selected log. | Leaves are treated as transparent by the working ray; the same log continues receiving mining progress. |
| 5 | Put a non-leaf solid collider between the villager and a selected log. | The worker rejects that stand, chooses another side of the same log and retains accumulated progress. |
| 6 | Apply knockback during tree mining or force a navigation failure with another entity/mod. | Mining pauses while out of reach; after return the same log remains the target and another stand is attempted if necessary. |
| 7 | Put a stone shovel in inventory or offhand while the task asks for a wooden shovel. | Stone shovel is equipped/accepted; no wooden shovel request is created. |
| 8 | Put a golden shovel in inventory while a stone-tier shovel is required. | Golden shovel is not treated as a higher harvest tier; the planner continues seeking stone or better. |
| 9 | Put a stone pickaxe in inventory while diamond ore is required. | Stone pickaxe is rejected for that objective; the planner asks for at least iron. |
| 10 | Put an iron pickaxe in inventory while obsidian is required. | Iron pickaxe is rejected; obsidian is routed to the mining stage and the planner requires diamond or netherite. |
| 11 | Place a crafting table one block from the villager; provide no crafting-table item. | A recipe requiring a table uses the existing block; the villager does not seek another table. |
| 12 | Place a furnace one block from the villager; provide no furnace item. | A smelting recipe uses the existing furnace; the villager does not seek another furnace. |
| 13 | Remove nearby crafting tables/furnaces but provide the corresponding item. | The villager places a temporary workstation on a safe solid floor, uses it, removes it and returns the item to inventory. |
| 14 | Start a complete (viable or failed) material plan outside workstation range, then move the villager into range of an existing table or furnace. | The stale plan is invalidated and recalculated with the new workstation context, whether the earlier plan was viable or failed. Table and furnace context are refreshed independently. |
| 15 | Fill the villager's inventory before a temporary workstation is returned. | The workstation item is dropped safely at the villager instead of being deleted. |
| 16 | Save/reload during a temporary-furnace smelting task. | The temporary furnace appears lit, smoke/flame particles are emitted at its position, and it is cleaned up after cooking or task abandonment; the furnace item is returned once. |
| 17 | Build and inspect both normal and zombie builder variants. | Both use the slate-blue hardhat, muted teal workwear and brown tool-belt texture, preserving the original UV layout and alpha mask. |
| 18 | Provide a wooden-shovel goal while a stone shovel exists in the main hand, offhand or inventory. | Stone is equipped or retained; no wooden shovel recipe/provider request remains. |
| 19 | Provide a crafting-table item but no nearby table; request a recipe that needs a table. | The builder places the temporary table, retains it for the 20-tick use window, then removes it and recovers it. |
| 20 | Save/reload while a temporary crafting table is in its use window. | The placed table and ownership state are restored; it is reclaimed once the crafting operation finishes. |
| 21 | Request a missing tool while there is no eligible professional supplier in range. | The supplier check completes, then the ordinary acquisition planner advances on the next tick instead of waiting a full lazy-planning interval. |

## Automated coverage

`MaterialToolCompatibilityTest` contains pure tests for same-family tool tiers, provider compatibility, wooden/stone/golden shovel and axe harvest tiers, diamond-ore and obsidian pickaxe requirements. `BiomeWoodProfileTest` covers Plains-to-oak mapping and search spiral coverage. These are not substitutes for live-world acceptance tests. The local environment used for packaging does not include a runnable Gradle wrapper/installation or Minecraft/Fabric runtime, so a full JUnit execution and live-server run remain pending in CI or a development setup.

