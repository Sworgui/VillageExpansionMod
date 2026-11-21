# House-first site clearing — 1.4.1

## Why builders appeared to wander

In 1.4.0 a new task started at `CLEAR_PATH`. The controller precomputed trees along the entire future village road and felled those trees before the house was built. A builder could therefore walk away from the selected plot, cut multiple route trees and spend a long time away from the actual construction footprint.

## New order

1. Search a compact 4–12 block radius around the village centre.
2. Accept moderate natural slopes and calculate a bounded set of cut/fill operations.
3. Clear vegetation and natural trees only where they overlap the house footprint/headroom.
4. Cut high natural terrain, then fill low columns with collected dirt/cobblestone.
5. Build the house.
6. Build the road from the house entrance; clear only obstacles that intersect the actual road blocks.

The site may vary by up to 6 blocks from its median floor, with a default maximum of 160 block operations. Existing solid/player-built structures, block entities and fluids remain protected. Terrain beyond the configured budget is not bulldozed.

## Save migration

A saved 1.4.0 task in `CLEAR_PATH` is converted to `LEVEL_SITE`. Its old route-wide tree target list and transient tree-cut state are discarded. The saved house site, blueprint, material goals and path remain intact. The future path is handled after the house is built.

## Work-position recovery

For terrain operations, the builder tries perimeter positions, then a safe position beside the next exact operation, then a compact search around the plot. A failed navigation target is not retried forever. For road trees, an unreachable tree is skipped locally and the road cursor advances.
