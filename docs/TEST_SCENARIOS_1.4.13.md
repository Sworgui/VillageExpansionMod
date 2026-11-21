# Material planning and responsiveness — 1.4.13

## Automated unit tests

- `MaterialPlanDecisionPolicyTest.directSourceIsPreferredWheneverItIsSupportedAndReachableByTheExecutor`: direct natural/mining routes beat recipe alternatives.
- `MaterialPlanDecisionPolicyTest.exhaustedPlanningBudgetNeverInventsARecipeRoute`: a recipe is not marked viable merely because an output is registered.
- `MaterialPlanDecisionPolicyTest.directSourceFallbackRemainsFiniteWhenRecursiveBudgetIsExhausted`: known natural sources retain a bounded fallback.
- `MaterialPlanDecisionPolicyTest.emptyRequirementHasZeroFallbackCost`: empty goals do not produce artificial costs.

## In-game acceptance tests

1. **Direct source versus modded recipe:** register/provide an alternate recipe for a collectable vanilla item. The builder should gather the direct source instead of entering the unrelated recipe chain.
2. **Recipe with unavailable dependency:** ask for an output whose recipe requires an unobtainable ingredient. The planner must mark the route unsupported rather than commit to an impossible ingredient chain.
3. **Several recipe alternatives:** provide one executable and one non-executable recipe for the same output. The executable route should win.
4. **Large modpack recipe index:** with thousands of registered recipes, observe `Planejando` progress across successive ticks; it must not remain on the same phase for 40 ticks.
5. **Twenty active builders:** verify that task updates and planning slices rotate fairly; no single builder should monopolize the planner cursor.
6. **Planning under construction load:** keep several builders placing blocks while another resolves a material recipe. Planning must continue because its slice is reserved before task updates.
7. **No natural resource nearby:** the builder should retry at the configured material interval and expand its search rather than spin every tick.
8. **Config migration:** start with a version-8 config; after loading 1.4.13 it should contain performance tuning version 9 and responsive defaults. Restarting must not overwrite subsequent user edits.

## Expected defaults after migration

- `builderTaskUpdatesPerTick = 2`
- `builderTaskMinIntervalTicks = 2`
- `spatialSearchIntervalTicks = 8`
- `lazyPlanningStepIntervalTicks = 1`
- `lazyPlanningRecipeEntriesPerTick = 128`
- `materialSearchIntervalTicks = 20`
