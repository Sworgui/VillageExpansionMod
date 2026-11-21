# Material dependency and planning regression tests — 1.4.14

## Unit tests

1. Defer `wooden_shovel`, then defer its immediate dependency `oak_planks`; the next resumed goal must be `oak_planks`, followed by `wooden_shovel`.
2. Extend the chain with `oak_log`; resume order must be `oak_log`, `oak_planks`, `wooden_shovel`.
3. Deferring the same parent again updates its required count but does not duplicate or reorder the stack.
4. A task saved before stack format version 2 has its legacy root-first deferred stack reversed once on load.
5. A viable vanilla recipe wins over a cheaper modded recipe; among recipes from the same origin, the lower score wins.

## In-game acceptance

6. Start a new builder with no planks, sticks or shovel in Plains. It should collect the biome-appropriate log, craft planks, make sticks if needed, craft a wooden shovel, and resume the original construction stage. It must not alternate indefinitely between `oak_planks` and `wooden_shovel`.
7. Repeat in Birch Forest, Taiga and Cherry Grove. The log/plank family must follow the home-site biome; the tool recipe remains the vanilla tool recipe.
8. Start from a save in which the task already contains a legacy suspended chain `wooden_shovel -> oak_planks`. After update, the builder must resume `oak_planks` first and then the shovel.
9. Install a mod/datapack recipe that makes a vanilla item through an unusual recipe. If a viable vanilla recipe exists, the builder must choose the vanilla recipe. If the vanilla route is impossible, a supported modded route may be used.
10. Test with 1, 5 and 20 active builders. Planning should remain round-robin; one worker must not starve others. Recipe indexing should advance only once per world tick.
11. Make a material goal temporarily unavailable. Its failure label should remain stable and retry after the configured 40 ticks by default, rather than locking the worker for 200 ticks.
12. Measure controller tick time in a large modpack. Planning must remain bounded; if the time budget is exceeded, remaining work should carry to later ticks rather than monopolize the server thread.
