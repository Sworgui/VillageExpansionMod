# Regression and performance scenarios — 1.3.9

These are manual in-game tests; they have not been simulated in this environment.

1. Spawn 20 villagers on separate free blocks in one dimension. Confirm all entities spawn and retain vanilla head/look/movement updates.
2. Confirm no more than 20 active builder tasks are assigned in the dimension, even when villagers are near multiple settlement anchors.
3. Confirm builder task labels advance slowly but consistently; with 20 workers each custom task slice should be spaced by approximately 40 game ticks per worker.
4. Check that resource-search log messages do not appear in bursts and that radius expansion still finds resources over repeated attempts.
5. Test a dense forest: route/tree detection should not cause a large single-tick stall, and builders must still avoid chopping persistent/player-built wood.
6. Test exposed stone, caves and a coal vein: ore searches must remain local to the current mine face and continue after each stair step.
7. Test a large house template: plot clearing and blueprint material counts must continue incrementally.
8. Unload/reload chunks containing builders; tasks must resume without duplicate assignments.
9. Restart the world with 20 saved tasks; the dimension-wide cap must not assign additional builders until existing tasks complete.
10. Profile 120 seconds with Spark. Compare MSPT with mod disabled, then enabled with 20 builders. Identify `Village controller slice`, `Builder task slice`, and vanilla `VillagerEntity`/navigation costs separately.

## Expected scheduling

At defaults, the controller invokes every 2 ticks, permits at most one expensive custom task slice per 2 ticks, and advances a given worker no more often than every 40 ticks. Vanilla entity AI and movement remain enabled every tick.
