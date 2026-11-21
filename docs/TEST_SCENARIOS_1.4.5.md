# 1.4.5 — log acquisition and biome-wood acceptance scenarios

These eight scenarios extend the earlier acceptance matrices documented in `TEST_SCENARIOS_1.4.3.md` and `TEST_SCENARIOS_1.4.4.md`. They require a Fabric server with Minecraft 1.21.1; they are not claimed as executed in this environment.

1. **Oak Forest template:** place the village center in a different forest biome but force the selected house plot into a regular Forest/Oak Forest. The blueprint must be reloaded from the final plot biome and request oak logs/planks, never spruce wood.
2. **Off-grid tree:** place a single natural oak tree at a column that is not one of the former 11x11 sample points. The builder must find it, path to it and harvest it.
3. **Search continuation:** keep the first 1,024 searched columns tree-free and place the matching tree in the next spiral slice. The next retry must find it without restarting at the center.
4. **No axe / no circular prerequisite:** give the builder no axe, request oak logs, and ensure the oak tree is reachable. The builder must start walking to the tree without waiting for an axe recipe.
5. **Species filtering:** place oak and spruce trees near a builder whose blueprint requests oak logs. The builder must choose oak and must not substitute spruce logs.
6. **Expanded search:** keep matching trees outside the initial radius, allow retries to advance, and verify the search radius expands while the Villager remains in the log-acquisition stage rather than idling permanently.
7. **Saved-task migration:** save a 1.4.4 builder in an Oak Forest while it is waiting for `spruce_log`, then load the world with 1.4.5. The pending goal and any deferred wooden recipe dependencies must migrate to oak-family items from the reloaded blueprint.
8. **Retry cooldown:** keep matching trees outside the current search radius but inside loaded chunks for several attempts. Confirm that a failed search does not restart on every controller tick, but resumes after the retry interval and continues from the next spiral slice.

## Automated checks

`BiomeWoodProfileTest` verifies biome wood selection, path remapping, full square coverage across search slices, uniqueness of visited columns and cursor continuation across radius expansion. Run with `gradle test` in the configured Fabric/Loom environment. The saved-task migration and in-world navigation cases require a running Fabric server.
