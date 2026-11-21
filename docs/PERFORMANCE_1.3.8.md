# Performance design — 1.3.8 (historical)

## Work scheduling

The controller has a per-dimension round-robin scheduler. Defaults are:

- `builderTaskUpdatesPerTick = 1`
- `builderTaskGlobalIntervalTicks = 1`
- `builderTaskMinIntervalTicks = 20`
- `lazyPlanningStepIntervalTicks = 20`
- `lazyPlanningRecipeEntriesPerTick = 4`
- `spatialSearchIntervalTicks = 8`
- `assignmentScanInterval = 10`
- `maxPathSearchNodes = 600`

With 20 active workers in one dimension, the controller advances one builder task per tick and round-robins through the workers. A given worker is therefore advanced about once every 20 ticks (approximately one action slice per second). Its vanilla Brain and navigation are not paused. Timers such as block-break progress use elapsed controller ticks so the less frequent controller updates do not freeze timers.

## Entity tracking

`ServerEntityEvents.ENTITY_LOAD` adds a villager to the loaded cache and queues task restoration. `ENTITY_UNLOAD` removes it from the live cache and snapshots an active task. At most one pending task restoration is processed per tick. The builder controller and breeding fallback no longer walk all world entities for periodic rediscovery.

## Bounded world work

- Dirt/direct material/clay scans: 48 sampled X/Z columns per search.
- Tree and stripped-log searches: 40 sampled X/Z columns per search.
- Dry clay stand search: 24 predetermined bank candidates.
- Mine entrance search: 8 directions per 12-block ring.
- Village path anchor search: 64 sampled columns.
- Tree discovery along a path: at most about 48 route anchors plus a small endpoint neighborhood.
- Tree log discovery: 32 queue nodes per scheduled builder slice.
- Plot vegetation scan: 16 X/Z columns or 32 plant cells per scheduled builder slice.
- Blueprint item count: 32 placements per scheduled builder slice, then O(1) cache reads.
- Sapling placement: 12 sampled sites and 8 local offsets; it does not sweep a full radius square.

The spatial-search permit serializes world scans per dimension. Search radii still expand across repeated failures, so a bounded attempt may take multiple seconds but does not create a large single-tick scan.

## Adaptive optional work

Recipe planning and village-founding completion are deferred when the controller has already consumed 2 ms of its current tick. The 2 ms value is a soft threshold, not a hard real-time guarantee: a single Java/world API call can still take longer. Task/assignment/controller slices log warnings at 10 ms.

## Limits

This reduces the mod's custom controller work, not Minecraft's own world ticking, entity AI/pathfinding, chunk loading, other mods, or server-side networking. The 20-builder target needs an in-game profile on the actual Cobblemon/modpack server. A server TPS/MSPT profiler (for example Spark) is needed to identify unrelated remaining costs.
