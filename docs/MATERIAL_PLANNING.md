# Material planning and tree harvesting

Village Expansion 1.2.1 uses a demand-driven material planner. The selected house template is converted into a map of block-item counts for logging and diagnostics. Before collecting a block item, the worker counts every still-placeable occurrence of that item from the current build index to the end of the blueprint. It recursively plans the recipe ingredients for that total, subtracts inventory already held, then gathers/crafts only the shortfall. The requested item and target count are saved with the task so the same request is resumed after a restart.

## How quantities are counted

- Each blueprint block placement counts as one block item.
- The upper half of a door and the head half of a bed do not count as additional items.
- A non-replaceable block already occupying a blueprint position is skipped; the builder does not consume an item for that position.
- Items already in the Villager's inventory are used before collecting more.
- Registered crafting recipes are followed recursively to request the exact ingredient quantities for all remaining placements of the requested output item. Recipe batches are respected; any extra output from a batch is kept for later placements/jobs.
- Registered smelting recipes run as timed tasks and use coal or charcoal. Construction planks, logs and sticks are not consumed as furnace fuel.

This means the builder does not stockpile a fixed amount such as 88 logs. For example, if the unfinished blueprint contains 40 oak planks and the Villager has none, it resolves the registered plank recipe to the required number of oak logs, gathers that amount, crafts the plank batches, and then uses those planks as the placements are built. If some planks are already in inventory, they are deducted before more logs are gathered. Different output item types are planned when their first placement is reached, keeping the inventory workload manageable.

## Material sources

| Requested item type | Source used |
|---|---|
| Logs / wood species | Confirmed natural trees of the required wood species |
| Saplings | Leaf drops from the selected tree; replanted on valid soil when space is available |
| Cobblestone / stone materials | Exposed cave stone or the mod's saved stair mine |
| Coal / ore items | Matching ore blocks in the loaded search area |
| Sand / clay | Matching surface terrain blocks |
| Wool | Shearing a living sheep of the required wool color |
| Glass / smelted ingredients | Registered smelting recipe, timed processing, coal/charcoal fuel |
| Planks, doors, beds, torches and other craftable blocks | Minecraft's registered crafting recipes |

## Tree safety

A wood resource candidate must start directly above natural soil, have at least three vertically connected log blocks and have a canopy of non-persistent leaves above the trunk. The harvester then follows every connected log inside its bounded tree volume, so it does not stop at lower trunk sections that are too far from leaves. Candidate roots near beds, doors, workstations or block entities are protected. The generic raw-material finder uses an explicit whitelist of natural raw resource blocks and no longer mines an arbitrary block simply because its item form matches a requested construction item. Once selected, the worker breaks connected trunk/branch logs individually, then breaks associated leaves so sapling drops can be collected.

All available saplings are planted (including nearby dropped sapling items when the Villager inventory is full) only where the block below can support them, the space is empty, light is sufficient, and the position is not in the active house footprint or reserved road. Saplings without a valid spot remain in the Villager inventory or as nearby item drops for a later planting attempt.

Minecraft does not store universal provenance metadata identifying every player-placed block. The tree test is therefore conservative and block-shape based; it is designed to avoid ordinary houses, but a player-built log structure intentionally shaped like a natural tree cannot be distinguished perfectly by vanilla block data alone.

## Current limitations

- Crafting and smelting use recipe data, inventory ingredients, timers and particles. The Villager does not yet place items into a physical crafting table or furnace block GUI.
- Recipe batch output can leave a small surplus. For example, one door recipe creates three doors; unused output is retained rather than destroyed. Felling one complete tree can also leave extra logs beyond the minimum recipe amount, because all connected tree logs are harvested together and saplings are replanted.
- The vanilla source finder knows trees, cave stone/ores, common terrain, sheep wool and registered smelting inputs. A third-party material with a custom gathering method needs an additional material-source compatibility entry or code adapter.
- If a required raw item cannot be found in loaded chunks or in the nearby search area, the Villager keeps the pending request and retries later rather than substituting an unrelated material.
