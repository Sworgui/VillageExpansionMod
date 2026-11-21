# 1.4.6 — biome palette, persistent mining and nearest-tree scenarios

These are server acceptance scenarios for Minecraft 1.21.1 + Fabric. They require a live world; they are not claimed as executed by the local pure-logic validation.

1. **Plains site overrides stale spruce request:** load a saved 1.4.5 task in Plains whose pending goal is `spruce_log`. The task must migrate to `oak_log`; the task label must show `bioma da casa: plains`. The resulting blueprint must not retain spruce-family blocks.
2. **House biome differs from worker biome:** place the worker just across a biome border while the selected house plot remains in Plains. The label/log must identify `houseBiome=plains`, `workerBiome=<other biome>`, select oak, and not accidentally use the worker's biome or old village-center biome.
3. **Plot biome changes while a task is active:** change/reselect the final plot from Taiga to Plains. Pending/deferred wood goals and remaining blueprint palette must migrate from spruce to oak; the old in-progress tree target must be discarded once because its requested species changed.
4. **Knockback during mining:** start breaking a natural oak log, have another mod push the Villager more than four horizontal blocks away, then let it return. The break animation may pause while away, but progress must resume instead of restarting at zero.
5. **BlockState property change:** while the same block instance remains at the same coordinates, toggle a compatible block-state property. The mining session must retain its accumulated ticks. Replacing the target with a different block type must still reset the session.
6. **Blocked side / leaves nearby:** obstruct the selected standing cell with another entity or modded collision, or place foliage around the nearest log. The controller must reject the failed stand, choose another work stand around the same log (up to four blocks), and must not skip the log or clear its break counter just because the first route failed.
7. **Nearest accessible tree:** put two matching natural trees in loaded chunks, with the closer one on a slope and the farther one on flat ground. The builder must rank by accessible work-stand distance and choose the closer reachable tree. It must not pick a different species when the blueprint requests oak.
8. **Profession texture:** verify the normal and zombie builder profession overlays load the new 64x64 slate-blue/teal texture after a resource reload. The custom hat must render fully without restoring the old yellow/gold palette.

## Automated checks

- `BiomeWoodProfileTest` includes explicit Plains/Meadow -> oak assertions and the existing remap/search-spread tests.
- `MiningProgressMathTest` verifies same-block/same-position session retention and horizontal reach pause policy alongside the vanilla mining-time formula.
- The in-world behavior above still needs a live Fabric server with the other collision/foliage mods used in the user's pack.
