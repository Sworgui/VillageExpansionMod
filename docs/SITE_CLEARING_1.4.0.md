# Site selection and clearing — 1.4.0

## Goal

Builders should not roam outward in search of a naturally perfect empty footprint. They search near the village and adapt a modestly uneven natural plot, then proceed to construction.

## Default limits

- Search radius: 4–18 blocks around the village centre.
- Four candidate footprints per assignment slice (assignment cadence remains globally rate-limited).
- Maximum terrain height difference from the median floor: 4 blocks.
- Maximum combined natural cut/fill operations: 96 blocks.
- Water, lava, block entities and existing solid/player-built structures remain rejection conditions.

## Execution

1. Clear route trees and plot vegetation incrementally.
2. Remove natural dirt/stone protrusions from the future house volume.
3. Cut higher columns to the median floor height.
4. Fill lower columns with dirt; use cobblestone only as a fallback when permitted by existing fill logic. Gather dirt and resume the exact pending fill if inventory is short.
5. Use a safe perimeter stand for leveling rather than a fixed coordinate inside the plot.
6. If the plot is already level, skip the leveling trip and begin BUILD.
7. If a tree-log work position is unreachable after adjacent and radius-two checks, skip only that individual log after bounded route recovery.

## Regression scenarios

- Flat meadow: after vegetation clear, no redundant trip to the plot corner; proceed directly to BUILD.
- Gentle 1–3 block slope: cut high cells, clear natural dirt/stone headroom, and fill low cells within the 96-operation budget.
- Steep slope or excessive earthwork: reject candidate and keep searching inside the compact configured radius.
- Forest: remove natural trees overlapping the plot and resume leveling; no endless wait on a tree whose every work stand is blocked.
- Dense tree: radius-two stand search finds an accessible point or skips the blocked log; the house task continues.
- Unreachable leveling stand: reject that stand and choose another point on the footprint perimeter.
- Existing chest, workstation, bed, door or solid player structure: reject candidate; never clear it as terrain.
- River/lava: reject fluid-occupied candidate.
- Save/reload during plot scan or leveling: existing cursors/stage remain valid; runtime stand cache is rebuilt.
