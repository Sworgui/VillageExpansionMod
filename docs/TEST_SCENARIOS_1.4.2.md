# Builder recovery and construction regression plan — 1.4.2

This suite is split into two layers. `src/test/java` contains deterministic JUnit tests for the pure mining-time calculation and navigation-recovery policy. The scenarios below require an actual Minecraft 1.21.1 Fabric server/world; they are acceptance tests, not claimed as executed by this source-only validation.

## Automated unit tests

Run with `gradle test` from the project root. This source archive does not include a Gradle wrapper.

| Test class | Coverage | Expected result |
|---|---|---|
| `MiningProgressMathTest` | Correct tool multiplier; suitable/unsuitable harvest divisor; zero and negative hardness; minimum speed clamp | Exact tick count derived from block hardness and the currently held tool; unbreakable blocks return `-1` |
| `BuilderRecoveryPolicyTest` | Every builder stage with an unreachable navigation target | Every stage has an explicit fallback; no stage silently returns a null/no-op policy |

## In-game acceptance matrix

For each scenario, enable `showBuilderTaskLabel`, keep the server log visible, and record the villager UUID, task label, target position and elapsed game ticks. Repeat with one builder and with 10–20 concurrent builders where relevant.

### Tool selection, mining and durability

1. **Oak log with wooden axe:** provide a wooden axe and a normal oak log. The expected duration is calculated from vanilla hardness and that axe's mining multiplier (approximately 30 game ticks for hardness 2 and speed 2), not the legacy fixed `blockBreakBaseTicks` value.
2. **Same log without an axe:** remove all axes. The planner must obtain/craft an axe before tree cutting; it must not silently spend the much slower bare-hand duration for normal tree harvesting.
3. **Different axe tiers:** compare wooden, stone, iron and diamond axes against the same log. Higher `getMiningSpeedMultiplier` values must reduce the duration proportionally.
4. **Axe breaks during a multi-log tree:** give the builder an axe with one durability point remaining. It must finish the current block, notice the broken/empty hand, obtain another axe, equip it, then continue the remaining connected logs.
5. **Backup axe in inventory:** keep a weaker axe in the inventory when the stronger held axe breaks. The builder must equip the backup before attempting to craft/collect another axe.
6. **No material for a replacement axe:** exhaust nearby planks/sticks and remove crafting alternatives. The project may pause in `Coletando Materiais`, but must retain its target and retry planning; it must not repeatedly attempt to mine with an empty hand or lose its build task.
7. **Pickaxe breaks while gathering cobblestone:** give the builder a one-durability pickaxe and require multiple stone blocks. It must acquire a replacement before scanning or mining the next stone target.
8. **Pickaxe breaks during an exact ore request:** request an ore requiring a stone/iron pickaxe, break the current pickaxe, then verify the original ore goal is deferred while the replacement-pickaxe goal is acquired. The ore target must not be discarded solely because the old pickaxe broke.
9. **Pickaxe absent at tunnel start:** force `MINE_TUNNEL` with no pickaxe. The worker must transition to material acquisition, return to mining after the tool is available, and not repeatedly reject every rock as unmineable.
10. **Wrong tool / empty hand:** mine a block with a non-suitable tool in a controlled test. Progress must use the slower vanilla divisor and the actual held-stack multiplier; changing the held item mid-break must cancel/restart progress rather than award drops for the previous tool.
11. **Zero-hardness and unbreakable blocks:** a zero-hardness target must finish in one tick; an unbreakable target must be skipped/abandoned with a log message, never retain an active break counter forever.
12. **Dropped item filtering:** cut trees, gather dirt, mine stone and collect one exact ore. Only the accepted drop category/item should be transferred to the builder inventory.
13. **Tool durability on each block:** verify each successfully broken block damages the held tool once, not once per controller slice or once per game tick. Repeat with the shovel during dirt collection and terrain leveling; a missing/broken shovel must be acquired before the next dig operation.
14. **Tool preservation across categories:** begin with an axe in the main hand and a pickaxe in inventory, then alternate between tree cutting, stone mining and dirt collection. Each tool must be swapped into the hand without deleting the previous main-hand tool; repeat with a full inventory to verify the inventory-slot swap remains lossless.

### Navigation and task-state recovery

15. **Unreachable tree root:** surround a sampled tree with impassable blocks. The location must be rejected, and the next wood search must choose a different resource instead of returning to the same root forever.
16. **Unreachable individual tree log:** block every adjacent/radius-two work stand for one log in a connected tree. The log may be skipped, but the tree task and house project must continue.
17. **Unreachable dirt stand:** surround a dirt block so its work stand cannot be reached. That target must be blacklisted and another dirt/stone source searched.
18. **Unreachable exposed stone:** wall off a cave work stand. The rock target must be rejected and the cave scan/tunnel fallback must proceed.
19. **Unreachable exact material block:** make the chosen direct-source block inaccessible. The planner must exclude that position and search another source for the same item.
20. **Unreachable professional supplier:** make the provider inaccessible or unload/remove it. Its offer must be temporarily rejected and the normal acquisition planner must resume.
21. **Unreachable mine entrance/front:** block the current tunnel approach. The entrance must be excluded for that task and a different entrance searched; it must not cycle back to the same registered entrance.
22. **Blocked road cell:** put an obstruction on one path cell. The path stage must skip that cell and continue rather than loop on one navigation target.
23. **Navigation overridden by vanilla Brain:** while the builder has a target, create a nearby bed, bell, workstation and random stroll target. The builder's custom destination must be reasserted after each Brain tick; WALK_TARGET must not pull it away.
24. **No active movement target:** during resource planning/smelting wait, verify vanilla stroll/work memories do not move the active builder away from its task position.
25. **Chunk unload/reload while travelling:** unload the builder or target chunk, then reload it. The task must remain saved; an unloaded target must be discarded/researched instead of being held forever.
26. **Save/reload during block breaking:** save while a block is partially mined, reload, and verify the block-break animation/progress restarts cleanly with no phantom drops or stale progress state.

### Profession lock and Brain overlap

27. **Composter beside builder:** place a composter and allow several day/night cycles. The active builder must remain on `village_expansion:builder`; `JOB_SITE` and `POTENTIAL_JOB_SITE` must be cleared during the task.
28. **Every vanilla workstation:** repeat with blast furnace, smoker, lectern, cartography table, fletching table, smithing table and other job-site blocks. No profession may be acquired while the construction task is active.
29. **Restore profession after completion:** finish an ordinary house and a professional house. The worker's original profession must be restored for ordinary construction; the planned profession/job site must only be applied at the intended professional-house completion step.
30. **Danger and sleep exception:** spawn a hostile mob, set the builder on fire, and test night-time HOME behavior. Work/navigation must pause for safety or sleep; on resume, the task and material goals must still be intact.

### Construction placement and multiple villagers

31. **Occupied work stand:** place another living entity on the closest work stand but leave another safe stand nearby. The search must choose the alternate stand rather than returning `null` because only its closest candidate was occupied.
32. **All work stands occupied/unreachable:** block all stand positions around one blueprint cell. After bounded searches/navigation failures, the one cell may be skipped and the rest of the blueprint must continue.
33. **Living entity inside target cell:** keep a villager/mob in the exact block placement volume. The builder waits up to 600 game ticks; after that it skips only the occupied cell and continues the project.
34. **Player block / block entity in blueprint:** put a chest, workstation or non-replaceable player block in the planned cell. It must not be destroyed; the placement is skipped and the task advances.
35. **Two builders, same resource area:** run two active tasks near the same trees/stone source. They must not reserve the same active target; a failed target in one task must not poison the other task's search.
36. **Two unemployed villagers in an existing settlement:** spawn two adult villagers with `NONE` profession and no HOME. Each eligible villager should receive its own reserved site/task over successive assignment scans; sites must not overlap, and completing one task must assign HOME to its worker without overwriting the other villager's memories.
37. **House for a different unemployed resident:** the current task model binds a construction task and its completion HOME assignment to the same worker UUID; it does not have a separate `resident UUID` field. The acceptance assertion for the current version is therefore that builder A does not accidentally claim builder B's home. If the intended design is explicitly for A to build and assign a house to B, that is a separate feature requiring resident ownership/reservation data and is not implemented by this bug-fix patch.
38. **Full inventory:** fill all villager inventory slots before mining/crafting. Verify item drops remain recoverable/collected later where supported and that no recipe inputs are consumed until its dependencies are available.
39. **No viable resource in range:** remove all safe sources for a required material. The worker should remain in an explicit material-planning wait/retry state with the project intact; it must not freeze navigation or corrupt its current blueprint index.
40. **20 active builders:** run up to the dimension cap. Verify one global state-machine slice per tick, round-robin progress, block breaking still advances per tick for every active worker, and no builder is starved indefinitely.
41. **Founding sequence:** test the first common house, founding professional house and bell/gold-block requirement. Resource/tool failures must preserve founding state and avoid duplicate house/site reservations.

## Limits of this checkout validation

The source archive does not contain a Gradle wrapper, the container has Java 21 but no `gradle` executable, and no cached Loom/Gradle dependencies are available. Therefore the JUnit and Fabric compilation tasks could not be run here. The unit tests and in-game matrix are included in the project, but they must be executed in a Gradle/Fabric development environment before treating the changes as runtime-verified.
