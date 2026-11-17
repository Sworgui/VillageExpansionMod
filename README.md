# Village Expansion (Fabric)

Mod para Fabric que faz os Villagers participarem da expansão da própria vila: procuram lotes, limpam árvores, preparam o terreno, coletam materiais, constroem casas e abrem caminhos até elas. A reprodução também pode acontecer sem camas livres, desde que os aldeões estejam alimentados e prontos.

## Alvo desta versão

- Versão **0.2.4**: partículas identificam os construtores e até três Villagers podem trabalhar simultaneamente em casas diferentes da mesma vila, depois da sequência inicial de fundação.

- Minecraft: **1.21.1**
- Java: **21**
- Fabric Loader: **0.16.14 ou superior**
- Fabric API: **0.116.17+1.21.1**
- Fabric Loom: **1.10.5**
- Versão do mod: **0.2.4**

> Esta entrega contém o **projeto-fonte**. O `.jar` não foi compilado nem testado dentro de uma instância real do Minecraft neste ambiente.

## O que o mod faz

### Fundação de novas vilas

- Se um Villager adulto não encontrar cama, sino, outro assentamento conhecido ou registro de vila próximo, ele pode se tornar o fundador de um novo assentamento.
- O centro inicial do assentamento é a posição em que o Villager foi encontrado. O construtor procura um lote próximo, limpa árvores e vegetação, nivela o terreno, coleta materiais e abre um caminho até a construção.
- A sequência inicial é especial: **1 casa comum com cama, seguida de 1 casa profissional com estação de trabalho**. Quando possível, a estação corresponde à profissão original do fundador (por exemplo, composteira para fazendeiro, alto-forno para armeiro, púlpito para bibliotecário).
- O fundador fica associado à vila durante as duas obras. A primeira cama não o impede de terminar a casa profissional; a cama só é atribuída como `HOME` depois que a sequência de fundação termina.
- A vila e o UUID do fundador são gravados no `PersistentState`, evitando que outros Villagers próximos iniciem construções de fundação sobrepostas.
- Depois das duas primeiras casas, o assentamento passa a usar a regra normal de expansão: **2 casas comuns para cada casa profissional**.
- Se um assentamento em fundação for carregado de um save, os dados de fundador e etapa inicial são restaurados. O progresso de uma obra individual ainda é runtime-only; uma construção interrompida pode precisar ser recomeçada.

### Expansão e construção

1. Procura Villagers adultos sem memória `HOME` (sem cama atribuída).
2. Localiza a vila usando a memória de cama de outro Villager, procurando um sino/uma cama existente nas redondezas ou consultando o registro persistente de assentamentos. Se nenhum assentamento for encontrado, o Villager pode fundar um novo.
3. Mantém os dados de expansão no `PersistentState` do mundo, por dimensão.
4. Organiza as construções na proporção **2 casas comuns : 1 casa profissional**.
5. Permite até **três construtores ativos por vila**. Durante a fundação, o fundador mantém a sequência obrigatória: primeiro a casa comum e depois inicia a casa profissional. Assim que essa segunda obra começa, até dois outros Villagers sem cama podem ajudar construindo casas comuns, sem disputar a estação de trabalho do fundador.
6. Reserva os lotes dos projetos em andamento, evitando que dois construtores construam no mesmo espaço.
7. Considera os projetos já reservados ao escolher entre casa comum e profissional, mantendo a proporção de 2:1 mesmo com construções simultâneas.
8. Procura um lote próximo da vila sem forçar o carregamento de chunks.
9. Planeja uma rota no terreno com A*; o ponto inicial é o caminho de terra (`dirt_path`) mais próximo da vila. Caso ainda não exista caminho, a rota começa no terreno próximo ao centro registrado da vila.
10. Limpa árvores e vegetação no trajeto antes de pavimentá-lo. Estruturas e obstáculos sólidos são tratados como células bloqueadas, em vez de serem destruídos para abrir passagem.
11. Constrói o caminho usando blocos vanilla `dirt_path`, reaproveitando os trechos que já fazem parte da rede da vila.
12. Nivela diferenças de até um bloco no lote, usando terra ou material de pedra carregado pelo Villager.
13. Derruba árvores e minera blocos de pedra; drops compatíveis são transferidos para o inventário interno do Villager.
14. Constrói uma casa 5×5 com paredes, telhado, porta, cama, iluminação e, nas casas profissionais, uma estação de trabalho.
15. Após concluir, registra a casa no save e atribui a posição da cama ao Villager construtor.

### Identificação visual dos construtores

- Enquanto uma obra estiver ativa, o Villager solta pequenas partículas verdes (`HAPPY_VILLAGER`) acima da cabeça a cada segundo. O efeito é enviado pelo servidor e permite identificar todos os aldeões encarregados de construção.
- Até três Villagers podem estar construindo simultaneamente na mesma vila. Durante a fundação, os ajudantes só entram depois que o fundador começa a casa profissional; eles recebem projetos de casas comuns. O limite é controlado por `MAX_BUILDERS_PER_VILLAGE` em `VillageBuilderController.java`.
- Os lotes ativos são reservados e o tipo de casa de cada nova tarefa considera as tarefas em andamento; assim, os construtores não escolhem o mesmo terreno e o ciclo de casas comuns/profissionais não é duplicado durante a atribuição.
- Durante uma obra, o Villager recebe temporariamente a profissão visual `village_expansion:builder`.
- A profissão usa uma textura própria em `assets/village_expansion/textures/entity/villager/profession/builder.png`: túnica de trabalho azul-acinzentada, avental de couro, cinto de ferramentas e detalhes nas mangas.
- Ao concluir ou abandonar a tarefa, o Villager recupera a profissão anterior. O identificador original também fica salvo em uma command tag da entidade, para recuperação se o servidor reiniciar durante a obra.
- A roupa é temporária: não substitui permanentemente profissões como fazendeiro, ferreiro ou bibliotecário.

### Reprodução sem camas

- Dois Villagers adultos podem gerar um bebê mesmo quando não existe cama livre.
- A mudança remove apenas a exigência de cama para o nascimento. Os requisitos de alimentação e prontidão continuam usando os métodos vanilla do Villager (`wantsToStartBreeding`, `canBreed` e `isReadyToBreed`).
- Os dois pais precisam estar prontos, sem estar negociando ou executando uma construção, e dentro de 8 blocos um do outro.
- A verificação ocorre a cada 200 ticks (10 segundos).
- Depois do nascimento, os pais recebem o cooldown vanilla de 6.000 ticks (5 minutos), e o bebê usa a idade vanilla de -24.000 ticks (20 minutos até a fase adulta).
- O bebê não recebe uma cama artificialmente. Quando crescer e continuar sem `HOME`, poderá ser escolhido como construtor e participar da expansão da vila.
- Villagers sem comida suficiente ou ainda em cooldown não se reproduzem. Portanto, não é uma geração automática ilimitada de população.

### Casas profissionais

As estações vanilla são alternadas com base na posição da casa: composteira, alto-forno, bancada de ferraria, defumador, barril, mesa de cartografia, mesa de arqueiro, púlpito, cortador de pedras, caldeirão, suporte de poções ou tear.

## Instalação

Como o mod adiciona uma textura de profissão, instale o mod tanto no **servidor quanto nos clientes** que vão visualizar os construtores. Todos devem usar Minecraft 1.21.1, Fabric Loader compatível e Fabric API.

### Servidor

1. Instale um servidor Fabric para Minecraft 1.21.1.
2. Coloque `village-expansion-0.2.4.jar` na pasta `mods` do servidor.
3. Coloque uma versão compatível da Fabric API na mesma pasta.
4. Instale o mesmo mod e Fabric API nas instâncias dos jogadores para carregar a textura personalizada.
5. Inicie o servidor. A lógica de construção e reprodução roda no lado servidor.

### Single-player

Instale Fabric Loader e Fabric API na instância 1.21.1 e coloque o `.jar` na pasta `mods`.

## Gerar o JAR a partir do código

Requisitos: JDK 21 e Gradle instalado.

Na pasta raiz do projeto:

```bash
gradle build
```

O arquivo será gerado em:

```text
build/libs/village-expansion-0.2.4.jar
```

## Ajustes rápidos

As constantes principais ficam em `VillageBuilderController.java` e `VillageBreedingController.java`:

- `ASSIGNMENT_SCAN_INTERVAL`: frequência de procura por novos construtores.
- `RETRY_DELAY`: intervalo para repetir uma tentativa sem lote ou sem recursos.
- `WOOD_REQUIRED`: unidades de madeira necessárias para a planta atual.
- `STONE_REQUIRED`: materiais de pedra necessários para a fundação.
- `NAV_SPEED`: velocidade usada na navegação até cada tarefa.
- `MAX_BUILDERS_PER_VILLAGE`: máximo de construtores simultâneos em cada vila (padrão: 3).
- `PARTNER_RANGE`: distância máxima entre dois Villagers para a reprodução sem cama.
- `SCAN_INTERVAL`: frequência da verificação de reprodução.
- `PARENT_COOLDOWN` e `BABY_AGE`: tempos de reprodução e crescimento.

O raio para escolher lotes fica entre 12 e 36 blocos do centro registrado da vila. A procura por madeira alcança 32 blocos do lote, e a procura por pedra alcança 22 blocos. A busca A* da estrada tem limite de 8.000 nós expandidos por projeto para evitar buscas ilimitadas.

## Limitações conhecidas da versão 0.2.4

- **Somente Minecraft 1.21.1:** versões posteriores precisam de atualização de mappings, dependências e testes próprios. O mesmo JAR não deve ser considerado compatível automaticamente com versões futuras.
- A identificação de uma vila existente usa `HOME`, sino, cama ou assentamento já registrado. Quando nenhum desses sinais é encontrado, o primeiro Villager adulto elegível pode começar uma nova vila; se o terreno não oferecer um lote ou recursos adequados, ele aguardará uma nova tentativa.
- A rota é calculada sobre a superfície já carregada e é limitada a 8.000 nós. Em terrenos muito íngremes, cercados por construções ou com grandes áreas não carregadas, o mod pode não encontrar um caminho; nesse caso, a casa pode ser construída sem pavimentação completa.
- Os caminhos usam `dirt_path` vanilla. Ainda não há mistura de cascalho, pedra, slabs ou materiais escolhidos por bioma.
- A limpeza de árvores identifica troncos próximos de folhas; árvores customizadas de outros mods podem não ser reconhecidas corretamente.
- O formato da casa é fixo e ainda não adapta sua arquitetura ao bioma ou à madeira local.
- A madeira e a pedra coletadas são itens reais no inventário do Villager. Para simplificar a construção, cada tronco representa quatro unidades de madeira; o mod não executa a receita vanilla de transformar troncos em tábuas item por item.
- Cama, vidros, tochas e estações profissionais são colocados pela planta sem uma cadeia completa de coleta de lã, areia, combustível e componentes.
- Ferramentas de madeira são equipadas temporariamente para representar o trabalho; durabilidade e crafting de ferramentas ainda não fazem parte do sistema.
- O progresso da construção fica em memória. Casas concluídas ficam registradas no save, mas uma parada do servidor no meio de uma obra pode deixar trabalho parcial no terreno. A profissão anterior é recuperada por command tag.
- A reprodução sem cama ainda depende da alimentação vanilla e de dois aldeões adultos prontos próximos. O mod não cria comida, não força encontro entre aldeões e não ignora o cooldown.
- A rotina não substitui completamente o cérebro vanilla do Villager. Interações complexas com outros mods de IA ainda precisam de testes.
- Lotes sobre areia, cascalho ou argila são evitados nesta versão, pois o sistema de aterro ainda trabalha apenas com terra e pedra.
- O inventário vanilla do Villager é limitado. Itens incompatíveis ocupando todos os slots podem impedir a coleta e interromper a obra.
- A seleção de terreno é conservadora e evita blocos com block entity. Não há proteção configurável por região nesta versão.

## Estrutura principal

```text
src/main/java/com/guides/villageexpansion/
  VillageExpansionMod.java
  BuilderProfession.java
  ai/VillageBuilderController.java
  ai/VillageBreedingController.java
  data/VillageExpansionState.java
src/main/resources/
  fabric.mod.json
  assets/village_expansion/textures/entity/villager/profession/builder.png
```

## Licença

MIT. Consulte `LICENSE`.

## Compilar online pelo GitHub Actions

O projeto inclui o workflow `.github/workflows/build.yml`. Para compilar sem instalar Java ou Gradle no computador:

1. Crie um repositório no GitHub (pode ser privado).
2. Envie para ele todo o conteúdo desta pasta, incluindo a pasta oculta `.github`.
3. Abra a aba **Actions** do repositório.
4. Se solicitado, habilite os workflows.
5. Faça um commit/push ou abra o workflow **Build Fabric mod** e clique em **Run workflow**.
6. Quando a execução terminar com sucesso, abra a execução e baixe o artefato `village-expansion-0.2.4` na seção **Artifacts**.

O workflow configura Java 21 e Gradle 8.12 no runner do GitHub, executa `gradle clean build` e disponibiliza o JAR por 14 dias. Se a compilação falhar, abra a etapa **Build mod** para consultar o log.
