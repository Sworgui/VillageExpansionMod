# Objective planner — 1.3.0

## Goal model

The blueprint is the list of final placement goals. For each placement, `requiredItemForBlock` yields the item goal. `ensureItemCountAvailable` recursively traverses the active world's registered crafting recipes, observes output batch sizes, gathers the missing ingredients, and retries the parent goal. Smelting recipes are represented as timed subtasks. `pendingMaterialItem`/`pendingMaterialCount` are the active leaf goal; the persisted `materialGoalStack` preserves every parent goal in dependency order while prerequisites are acquired. This replaces the old single deferred-goal slot for new saves and migrates that slot from 1.2.x saves.

## Resource-source policy

| Goal | Preferred viable source | Tool prerequisite |
|---|---|---|
| Logs | Confirmed natural tree of matching species | Axe |
| Stripped logs | Matching natural log; strip first, then harvest | Axe |
| Planks/sticks/crafting table | Registered crafting recipe from logs/planks | None / 2x2 table recipe |
| Cobblestone | Remembered local quarry or saved stair mine | Wooden pickaxe |
| Coal | Coal ore vein | Wooden pickaxe |
| Raw iron/copper/gold, lapis, redstone | Matching ore vein | Stone pickaxe |
| Diamond/emerald | Matching ore vein | Iron pickaxe |
| Wool | Shear a living sheep of the required color | Crafted shears + crafting table |
| Glass and other smelted outputs | Registered smelting input and coal/charcoal | Furnace process is simulated by the task controller |
| Flowers used by recipes | Exact matching flower block | Hand/shovel as applicable |

Hostile drops are not selected as substitutes when a peaceful source exists for the goal. In particular, beds are planned from wool and sheep, not string and spiders.

## Worksite discipline

- A successful stone source establishes a quarry centre. Subsequent stone collection is limited to a small radius around that centre; only when the local work area is exhausted is a new source selected.
- Quarry state is persisted across unload/reload.
- Navigation recovery can place a single step from carried dirt/cobblestone when the target is higher and the adjacent placement cell is safe and unoccupied.
- Construction checks the existing world before each operation. A level operation already satisfied by the terrain is skipped. Occupied/non-replaceable blueprint cells are not overwritten.
- House construction precedes path construction. The path is widened to three blocks and follows the template's entrance-side goal.

## Important scope note

This release is an incremental objective-planning refactor, not a fully autonomous Minecraft player simulation. Recipe crafting and smelting are still simulated from recipe data; villagers do not open a physical crafting-table/furnace GUI. Path widening follows the current A* centreline and validates each lane's surface; irregular terrain may omit individual side cells. Step placement is a limited recovery action, not general block-bridging or parkour. Validate these behaviors in a test world before deploying to a long-running save.
