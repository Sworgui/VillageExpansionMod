# Performance design — 1.3.9

## Scheduling budget

Defaults are intentionally conservative:

- Controller callback: every 2 game ticks (`taskTickInterval = 2`).
- One expensive builder task slice every 2 ticks (`builderTaskGlobalIntervalTicks = 2`, `builderTaskUpdatesPerTick = 1`).
- A given worker is not advanced more often than every 40 ticks (`builderTaskMinIntervalTicks = 40`).
- Hard limit: 20 saved/active construction tasks per dimension (`maxActiveBuildersPerDimension = 20`). `maxBuildersPerVillage` is an additional local limit.
- Assignment: at most one candidate attempt per 40 ticks.
- Spatial resource search: one permit per dimension every 20 ticks.
- Lazy planning: one phase every 40 ticks, with 2 recipe entries indexed per tick.
- Navigation target reassertion: 40 ticks. Vanilla Brain and movement continue each game tick.

With 20 workers, this targets approximately 10 custom task slices per second per dimension, or roughly one custom update per worker every 2 seconds. It deliberately makes projects progress more slowly to avoid main-thread spikes.

## Expensive search bounds

- Resource discovery uses capped random samples and heightmap-backed bounded-depth surface lookup.
- Cave scan grid is smaller; exact vein probing is constrained to a radius-2 region around the newly exposed mine face.
- Tree canopy detection uses 175 sparse block probes instead of 1,053; building protection uses a 7x8x7 neighborhood instead of 13x11x13.
- Path-tree discovery samples at most 24 anchors.
- Road path planning uses a four-way A* with a hard maximum of 300 expanded nodes.
- Tree connected-component scan remains capped at 32 nodes per worker slice. Plot vegetation and blueprint indexing remain incremental.
- Sapling placement uses a small candidate sample and retries are spaced after a denied shared spatial-search permit.

## Event-driven entity cache

`ServerEntityEvents.ENTITY_LOAD` / `ENTITY_UNLOAD` update the loaded-villager cache. The controller and breeding fallback do not periodically iterate all entities in a world. Restoring saved task state remains limited to one villager per controller invocation.

## What this does not guarantee

The scheduler limits the mod's own custom work; it cannot preempt a single Minecraft world API call once started. It does not throttle vanilla entity ticking, vanilla navigation/pathfinding, chunk loading, block/fluid ticks, other mods, or networking. The 20-worker target must be measured in the user's actual Cobblemon/modpack instance with Spark or another MSPT profiler.
