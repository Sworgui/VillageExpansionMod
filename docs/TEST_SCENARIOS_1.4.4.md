# 1.4.4 — material recovery and biome wood acceptance matrix

## Automated unit tests

- Regular `forest`, `oak_forest` and `flower_forest` resolve to oak; a spruce template log is remapped to oak.
- Birch, dark forest, taiga, jungle, savanna, mangrove, cherry and pale-garden biomes resolve to their expected tree family.
- Stripped logs, doors, stairs, hanging signs and other recognized wooden block paths are remapped while non-wood block paths remain unchanged.
- Tree search grid is deterministic, includes its center, covers the radius and shifts between retry phases.

## In-game acceptance tests (Fabric server, Minecraft 1.21.1)

1. Spawn a builder with an empty inventory in a regular Forest/Oak Forest with natural oak trees nearby. It must walk to an oak tree and begin harvesting without first crafting an axe.
2. Repeat with a builder in Taiga. It must request spruce logs and harvest spruce trees, not oak trees.
3. Spawn in a Birch Forest, Dark Forest, Jungle, Savanna, Mangrove Swamp, Cherry Grove and Pale Garden. Verify selected house blocks use birch, dark oak, jungle, acacia, mangrove, cherry and pale oak wood respectively.
4. Verify stairs, slabs, doors, trapdoors, fences, gates, signs and log axis/facing properties are preserved after wood-family conversion.
5. Remove all matching trees within the initial search radius. The builder must remain in the material stage, expand the search and move when a matching tree becomes visible in a later scan.
6. Place a player-built log wall with nearby leaves and a bed/door. The resource scanner must not classify it as a natural tree or dismantle it.
7. Give the builder a pickaxe in its main hand but no axe. It must keep the pickaxe rather than deleting it and still be able to harvest a log.
8. Give the builder a damaged axe which breaks on the first log. It must continue harvesting by hand or switch to another axe without losing other held items.
9. Verify a builder with enough matching logs resumes the saved BUILD stage and consumes only the item requested by its current blueprint.
10. Repeat tests 1–9 with two builders in the same settlement; reserved tree targets must not cause either worker to remain idle indefinitely.

These are acceptance scenarios, not claims of execution in a live Minecraft world. The unit tests are designed to run with `gradle test`.
