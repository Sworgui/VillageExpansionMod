# Test scenarios — 1.4.0

1. Spawn a homeless villager beside a flat meadow (within 4–18 blocks of village centre); confirm it assigns a house and enters BUILD without an unnecessary LEVEL_SITE walk.
2. Use terrain with a 1-block slope; confirm cut/fill operations flatten it.
3. Use a 2–3 block slope with a shallow dirt/stone protrusion in the footprint; confirm natural blocks are cleared and lower cells filled.
4. Use a slope or obstruction that would exceed 64 operations; candidate should be rejected without destroying structures.
5. Put a tree partly over the plot; confirm it is felled, plot vegetation clears, then building starts.
6. Create dense undergrowth around a trunk; confirm radius-two work stand selection.
7. Make one tree log unreachable; confirm the project skips only that log after bounded retries and continues.
8. Place a chest/workstation/door near candidate; confirm the candidate is rejected and no protected block is broken.
9. Put water/lava within candidate footprint; confirm site rejection.
10. Block the first perimeter work stand; confirm another is selected.
11. Save and reload during tree clearing and terrain leveling.
12. Repeat with five and twenty builders and compare MSPT using Spark.
