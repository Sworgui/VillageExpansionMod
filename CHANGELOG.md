# Changelog

## 0.2.4
- Adds a green happy-villager particle sparkle above every villager with an active construction assignment.
- Allows up to three active builders per village; up to two assistants may build ordinary houses while the founder works on the starter professional house.
- Reserves active build plots and active wood/stone targets so concurrent workers avoid overlapping house sites and resource extraction.
- Accounts for active projects when selecting ordinary versus professional houses, preserving the 2:1 ratio under concurrent construction.
- Removes a duplicate command-tag removal call in builder profession cleanup.

## 0.2.3
- Adds founding of a new settlement by an eligible villager without an existing village.
- The founding villager builds one ordinary house and then one professional house before normal expansion begins.
