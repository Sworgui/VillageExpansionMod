# Regression scenarios — 1.3.5

## Crash from 1.3.4: tagged-material estimate receives a null Villager

1. Start a server with Village Expansion enabled and no active builder tasks.
2. Spawn or allow an adult unemployed villager without a HOME memory to become eligible.
3. Let `tryAssignBuilder` select a house template containing at least one recipe-backed item whose ingredients include a tag alternative (for example, wood/planks or stone-tool materials).
4. Confirm builder assignment completes without an `Exception ticking world` crash.
5. Confirm material estimates use the candidate builder's current inventory, equipped tool and nearby professional suppliers.

## Alternative ingredients and recipe batch sizes

1. Make at least two ingredient alternatives available through the recipe's ingredient tag.
2. Put one alternative in the builder's inventory and make a professional supplier offer the other.
3. Confirm the estimator chooses based on the actual builder context, including existing inventory and supplier availability.
4. For an output recipe requiring multiple batches, confirm ingredient cost is estimated for the batch count rather than a single unit.

## Defensive null handling

1. Exercise planner public entry points with an invalid/null entity in an isolated test harness, where available.
2. Confirm invalid contexts produce a non-viable result or zero inventory count instead of throwing.

## Compatibility notes

- `No data fixer registered for cobblemon:*` messages, Xaero online-version cache expiry, FancyMenu missing texture paths, and Cobblemon `.old` player-data messages are separate from this Village Expansion stack trace. They should be triaged independently if they continue after this crash is fixed.
- Gameplay simulation and the full Fabric Gradle build must still be run by GitHub Actions / in a Minecraft test instance.
