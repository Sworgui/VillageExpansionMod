# Regression scenarios — 1.3.6

## Main-thread responsiveness / first builder

1. Start a fresh world with the Village Expansion mod and the Cobblemon Official Modpack (or another modpack with many item/recipe tags).
2. Spawn one adult unemployed villager in a safe, flat area.
3. Observe the server for at least 30 seconds after the assignment scan. Check that TPS remains stable and there are no repeating long server-tick warnings.
4. Confirm that the first builder receives a task, changes to the builder appearance, and begins moving/working.
5. When it needs a direct natural resource, confirm the target is selected once and reused while walking, rather than rescanned from the full radius every tick.
6. Spawn at least three more villagers using spawn eggs on separate, unoccupied blocks (not on the first villager's occupied position). Confirm every entity appears. The builder cap may leave some unemployed, but must not cancel their spawn.

## Large ingredient tags

1. Use a modpack where `minecraft:logs`, plank, stone, or common crafting ingredients include many modded alternatives.
2. Trigger builder assignment and construction of a recipe-backed block.
3. Confirm the planner completes within a normal server tick budget and emits no repeated long server stalls.

## Bounded planner behavior

1. Trigger a goal whose ingredient tag has more than 16 alternatives.
2. Confirm candidates already held by the builder and candidates matching the preferred tag are considered first.
3. Confirm recursive evaluation stops after the per-decision budget and returns a bounded fallback estimate instead of blocking the world tick.
4. Confirm the executor can still fall back to natural collection, professional suppliers, or crafting if the first candidate cannot be acquired.
5. Confirm a failed acquisition observes the retry cooldown; it must not rebuild the recursive plan every tick.

## Multiple builders

1. Set `maxBuildersPerVillage` to 3.
2. Spawn at least 4 unemployed adult villagers with enough space around them.
3. Confirm the first three can be assigned according to the founding-house rules, while the fourth remains a normal unemployed villager until a slot is free.
4. Confirm all spawned entities remain present; the builder cap limits active construction tasks, not entity spawning.

## Safety / save-load

1. Save and reload while one villager has an active dependency goal and another is waiting for assignment.
2. Confirm task state restores and no planner budget or cached result leaks between server ticks.

## Idle behavior when no construction destination exists

1. Let a builder wait for a resource-search cooldown with `moveTarget == null`.
2. Confirm the controller does not forcibly stop the vanilla navigation/idle brain every tick.
3. Confirm the villager can turn its head and use vanilla ambient behavior while it waits.
4. When a construction target becomes available, confirm the controller takes navigation priority again.
