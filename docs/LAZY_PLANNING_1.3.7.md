# Lazy Planning — 1.3.7

## Design

- A material request creates a `MaterialPlanningJob` keyed by output item and count.
- The job evaluates one phase per scheduler slice: direct peaceful/natural source, crafting, stonecutting, then smelting.
- The controller advances at most one job slice per world/controller tick, using UUID round-robin selection for fairness.
- Recipe indexing uses `RecipeManager.keys()` as a retained stream iterator; the controller resolves and inspects at most `lazyPlanningRecipeEntriesPerTick` entries per controller tick.
- It intentionally avoids `listAllOfType()`, which creates full filtered lists and can introduce a one-time spike for each recipe type.
- After a supplier scan, index batches continue on successive ticks (`+1`) until the shared index is ready. The later material-comparison phases use the normal `lazyPlanningStepIntervalTicks` delay.
- Each recursive estimation slice has a maximum of 24 planner steps and one source-world scan.
- Parent choices remain cached while child material goals are completed. These recipe references are transient; the requested item and dependency stack remain saved and the plan is rebuilt after reload.

## Defaults

- `lazyPlanningStepIntervalTicks = 4`
- `lazyPlanningRecipeEntriesPerTick = 32` (entries per tick while the shared index is being built)
- `MAX_PLANNER_STEPS = 24`
- `MAX_SOURCE_SCANS = 1` per evaluation slice
- One active planning slice globally per dimension/controller tick

## Expected behavior

A new material goal can take several additional seconds to resolve in a large modpack. During that time the villager should remain responsive, preserve its task label, and not repeat a recursive estimate each tick. Several builders should take turns progressing their plans.
