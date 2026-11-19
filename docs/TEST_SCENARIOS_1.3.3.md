# Test scenarios — Village Expansion 1.3.3

These are manual in-game regression scenarios. They are documented for GitHub/in-game verification and were not simulated in this environment.

1. **Toolsmith supplies a pickaxe**: place an adult toolsmith within 40 blocks with an available offer whose output is a stone, iron or diamond pickaxe. Make a builder need that tool. Confirm it walks to the toolsmith, receives the pickaxe without paying emeralds or input items, equips it and resumes the parent goal.
2. **Any profession can supply a matching output**: repeat with a farmer, shepherd, fisherman, fletcher, armorer, weaponsmith, toolsmith, mason or librarian whose active offer output matches the requested item. The profession name must not be hard-coded to a particular item.
3. **No provider beyond the radius**: place the only matching professional 41 blocks away with default settings. Confirm the builder does not use that supplier and starts the normal self-acquisition plan.
4. **Configurable radius**: set `professionalResourceSearchRadius` to 64 and place a supplier at 50 blocks. Confirm it becomes eligible after a server/world restart.
5. **One offer, multiple uses**: request more items than one output stack, while the provider's offer still has multiple available uses. Confirm the builder receives multiple stacks in one visit, with one offer use reserved per delivered stack.
6. **Exhausted offer**: set the matching offer to its maximum use count. Confirm it is ignored and the builder uses another matching provider or falls back to crafting/collection.
7. **Restock**: after free acquisition exhausts an offer, allow the provider's normal restock schedule. Confirm the offer can become available to builders again.
8. **No matching offer**: place a professional within range whose active offers do not output the requested item. Confirm that the villager is not visited.
9. **Better tool of same family**: request a wooden or stone pickaxe while a professional offers an iron/diamond pickaxe. Confirm the stronger pickaxe satisfies the prerequisite. Repeat with an axe and shovel.
10. **Wrong tool family**: request a pickaxe while the only provider output is an iron axe. Confirm the axe does not satisfy the pickaxe requirement.
11. **Inventory full**: fill the builder inventory before arrival. Confirm no stock use is consumed when zero items can be accepted, and the builder exits the supplier stage instead of orbiting indefinitely.
12. **Provider unavailable during travel**: remove or move the professional after the builder starts walking. Confirm the saved target is rejected, another supplier is searched, or self-acquisition resumes after the visit timeout.
13. **Save/reload while travelling**: save during `GATHER_FROM_PROFESSIONAL`, reload, and confirm the builder restores the supplier UUID or safely resumes planning if the provider no longer exists.
14. **Two matching professionals**: place two suppliers within range; confirm the nearer one is selected first and the second can be used if the first has no stock or cannot be reached.
15. **Default disabled setting**: set `professionalResourceSharing` to false. Confirm the builder does not visit professionals and follows the regular recursive material plan.
16. **Danger pause**: introduce a hostile mob during a supplier visit. Confirm existing danger handling takes priority and the saved material goal resumes after danger clears.

## Build verification

Run the repository GitHub Actions workflow with Java 21 and Gradle 8.12. Confirm it uploads `village-expansion-1.3.3.jar`.
