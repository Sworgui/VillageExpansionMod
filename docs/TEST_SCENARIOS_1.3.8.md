# Regression / performance scenarios — 1.3.8

1. Spawn 20 adult unemployed villagers with spawn eggs in separate free blocks. Confirm all entities spawn and their vanilla Brain/look/navigation remain responsive.
2. Confirm all 20 are registered; task assignment should proceed over several seconds without a single large planning spike.
3. Use `/spark profiler` or an equivalent profiler over at least 5 minutes and compare MSPT with the mod disabled and with 20 builders active.
4. Verify the log reports no repeated controller/task slices above 10 ms under ordinary flat-world construction.
5. Force several builders to seek wood, dirt, cobblestone, coal, clay and flowers; verify searches happen in separate time slices and chosen targets are reused while walking.
6. Build on dense tree-covered land and on a mostly open plain; verify plot scan and tree cutting advance incrementally.
7. Complete a house and road; verify path tree discovery stays bounded and path placement resumes across multiple controller slices.
8. Spawn/unload/reload a chunk containing active builders; confirm their tasks and profession state resume without a full-world scan.
9. Restart the server with active builders; verify each saved task is restored incrementally and no villager remains permanently stuck in the pending-load queue.
10. Confirm that normal villager movement and combat fleeing remain responsive while custom task updates are throttled.

A source/static check is not a substitute for the above runtime tests. Record MSPT, TPS, CPU and the new 10 ms warnings from the target modpack.
