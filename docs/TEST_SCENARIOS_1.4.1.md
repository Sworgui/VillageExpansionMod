# Test scenarios — 1.4.1

These are manual in-game regression scenarios. Static source checks are not a substitute for a Minecraft runtime test.

1. **Tree between village and house:** spawn a builder with several trees along the future route but no tree inside the house footprint. It should not walk the route first; it should start house-site preparation/building. The route is processed only after the house.
2. **Tree in footprint:** place a natural tree whose canopy overlaps the house footprint. The builder should fell its connected logs from a nearby standing position and continue to leveling/building.
3. **Flat plot:** choose a flat, clear plot. It should skip terrain work and proceed directly to `BUILD` after the incremental plot scan.
4. **Uneven plot:** use a footprint with up to 6 blocks of variation from its median floor and a total work budget below 160. The builder should cut high terrain and fill low columns using collected dirt/cobblestone.
5. **Steep/over-budget plot:** provide a candidate requiring more than the configured variation or operation budget. It should reject the candidate and test another local candidate, without wandering away from the village.
6. **Blocked work perimeter:** surround the footprint perimeter with obstacles but leave a safe adjacent cell near the next operation. The builder should use the operation-local work position.
7. **Legacy active task:** load a world with a saved 1.4.0 task at `CLEAR_PATH`. It should migrate to `LEVEL_SITE`, preserve house progress/material state, and stop cutting all route trees in advance.
8. **Tree blocks road:** after the house is built, put a tree directly on a road block. The builder should attempt local removal; if unreachable, it should skip that road cell rather than pursue the tree indefinitely.
9. **Player structure protection:** put a chest, workstation or player-built solid block in the footprint/headroom. Site selection should reject it or per-operation validation should skip it; it must not be demolished.
10. **Twenty builders:** run 20 concurrent tasks and verify the house-first order remains round-robin within the global task budget.
