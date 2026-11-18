# Third-party profession and house compatibility

Village Expansion 1.1.0 can load optional profession-house definitions from JSON files in `config/village_expansion/compat/`. Compatibility is data-driven: Village Expansion does not need to compile against the third-party mod.

## Example

Copy `examples/compat/examplemod.json` to `config/village_expansion/compat/examplemod.json` and replace the example IDs with IDs from the installed mod.

```json
{
  "modId": "examplemod",
  "professions": [
    {
      "profession": "examplemod:alchemist",
      "workstation": "examplemod:alchemy_table",
      "structures": {
        "plains": "examplemod:village/alchemist_plains",
        "desert": "examplemod:village/alchemist_desert",
        "default": "examplemod:village/alchemist_plains"
      },
      "weight": 1
    }
  ]
}
```

- `modId`: the Fabric mod ID. The entry is ignored while that mod is not installed.
- `profession`: registered Villager profession identifier.
- `workstation`: registered block identifier used by that profession.
- `structures`: structure-template identifiers (`namespace:path`) keyed by Village Expansion's style names: `plains`, `desert`, `savanna`, `taiga`, `snowy`, or `default`.
- `weight`: relative selection weight among available configured third-party houses.

The referenced NBT structure must be available through the server's `StructureTemplateManager` (for example, `data/examplemod/structure/village/alchemist_plains.nbt`). For the template to be accepted, it must contain at least one bed and the exact configured workstation block. The workstation is used to identify and assign the registered Villager profession when the house is finished. The villager receives a `JOB_SITE` memory at that workstation.

Vanilla professions and vanilla village templates continue to work when no compatible third-party entry is available. Compatibility files are loaded on server startup; restart the server after editing them.

This framework cannot infer arbitrary profession semantics from a mod that does not register a Villager profession / workstation block or does not expose a structure template. Such mods need a small dedicated compatibility file or future adapter.
