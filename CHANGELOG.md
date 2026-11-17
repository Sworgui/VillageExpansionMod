# Changelog

## 0.2.0

- Added an A*-planned dirt path between the existing village road network (or the settlement centre) and each new house.
- Builders clear trees and vegetation along the route before placing the path. Existing dirt-path blocks are reused.
- Added a custom temporary `village_expansion:builder` profession and a dedicated 64x64 profession texture (work tunic, leather apron and tool belt). The builder restores its previous profession after completing or abandoning the project.
- Original profession is stored in an entity command tag so an interrupted project can restore the villager correctly after a server restart.
- Added bed-independent villager breeding: two adult villagers can have a baby when vanilla food/readiness conditions are met, even if no free bed exists. Vanilla-style parent cooldown and baby growth age are retained.
- Updated mod version to 0.2.0.

## 0.1.0

- Initial village expansion controller, terrain preparation, resource gathering and house construction.
- Common/professional house ratio of 2:1.
