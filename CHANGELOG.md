# Changelog

## 0.2.2
- Corrigido o carregamento da lista NBT de casas: `NbtList` não possui `getLong(int)` na API Yarn 1.21.1. Agora o código lê o elemento como `NbtLong` e usa `longValue()`.
- Atualizados a versão do mod, o nome do artefato do GitHub Actions e a documentação para 0.2.2.

## 0.2.1
- Corrigido o import de `Heightmap`: no Yarn para Minecraft 1.21.1, a classe pertence a `net.minecraft.world`.
- Atualizados o número da versão e o nome do artefato no workflow do GitHub Actions.

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
