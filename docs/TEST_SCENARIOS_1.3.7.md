# Test scenarios — 1.3.7

1. Spawn one unemployed villager. Confirm it continues vanilla look/idle behavior while its first material plan is indexed and evaluated.
2. Spawn four or more villagers in separate positions. Confirm all entities appear immediately and builder plans advance in UUID round-robin order.
3. Use a template requiring a crafting recipe, a stonecutting recipe, and a smelting recipe. Confirm these are evaluated over separate planning slices, not in one large call.
4. Use a modpack with a large recipe registry. Confirm the planner retains a `RecipeManager.keys()` iterator and inspects no more than `lazyPlanningRecipeEntriesPerTick` entries per tick.
5. Test an empty recipe manager and a mixed registry containing crafting, smelting, stonecutting, and unrelated recipe types. Confirm indexing completes without losing supported types.
6. Save/reload while the recipe index and a material goal are in progress. Confirm the persisted material goal resumes and transient recipe references are rebuilt.
7. Manually add the requested item while a plan is queued. Confirm the inventory check completes the goal without waiting for remaining planner phases.
8. Make all known routes impossible. Confirm the item remains queued for retry rather than crashing or freezing the server.
9. Watch server timings/logs with several builders. Planning should advance no more than one bounded slice per controller tick per dimension.
10. Change recipe data packs and reload resources. Confirm the index is rebuilt against the new recipe manager generation.

## Static checks

- Config defaults and bounds are validated by the config loader.
- Java syntax parser reports no parse errors.
- Recipe index cursor transitions through empty and populated key streams and only publishes after iteration completes.
- Parent plan cache is keyed by requested item and invalidated when the requested count changes.

## Runtime checks still required

These are manual in-game scenarios. They have not been simulated in this build environment. The Gradle build and a multi-villager server profiling test must be run through GitHub Actions / the target modpack before treating the update as runtime-validated.
