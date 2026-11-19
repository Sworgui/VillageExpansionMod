# Test scenarios — Village Expansion 1.3.2

These are manual in-game regression scenarios; they are not represented as completed simulations.

1. **Toolsmith provides pickaxe**: spawn an unemployed builder and an adult toolsmith within 40 blocks. Ensure the toolsmith has an available offer that outputs a stone/iron/diamond pickaxe. Request an ore requiring that tier. Confirm the builder walks to the toolsmith, receives the pickaxe without emeralds/inputs, equips it, and resumes mining.
2. **No professional within radius**: place a matching toolsmith 41+ blocks away. Confirm the builder does not use that provider and follows normal crafting/mining prerequisites.
3. **No matching offer**: place a professional nearby whose current offers do not output the requested item. Confirm the builder falls back to the recipe/source planner.
4. **Exhausted offer**: use all available offer capacity before the builder arrives. Confirm disabled offers are ignored and another provider or self-acquisition is selected.
5. **Restock**: allow a provider to restock after a prior free transfer. Confirm the offer becomes available again and can supply another builder.
6. **Farmer resources**: request an item sold by a farmer (for example a crop/food output). Confirm free transfer from its offer, no emerald deduction, and construction resumes.
7. **Shepherd/tool provider**: verify matching wool/shears outputs can be used if present; otherwise the builder follows the existing sheep-shearing route.
8. **Tool upgrade compatibility**: request a wooden or stone tool while a professional offers a stronger tool of the same family. Confirm the stronger tool satisfies the prerequisite. A stronger tool must not satisfy a different tool family.
9. **Inventory capacity**: fill the builder inventory before it reaches a provider. Confirm no offer is consumed unless at least one item is accepted and check the builder can recover without looping indefinitely.
10. **Save/reload during travel**: save while the builder is walking to a professional, reload, and confirm the provider UUID/goal resumes or safely falls back if that villager is gone.
11. **Multiple providers**: place two matching professionals within 40 blocks. Confirm the nearest available provider is selected and an exhausted/missing first provider causes a second search.
12. **Safety**: spawn a hostile mob during the visit. Confirm the existing danger pause takes priority and the provider goal resumes after danger clears.

Build validation should be performed with GitHub Actions using Java 21 and Gradle 8.12.
