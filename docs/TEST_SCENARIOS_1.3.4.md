# Test scenarios — Village Expansion 1.3.4

## Purpose

This patch fixes the 1.3.3 compile error caused by `VillageBuilderController` serializing and using supplier-visit state that had not been declared on `BuilderTask`. It does not intentionally change supplier selection or free-transfer behavior.

## Static/source checks

- [x] `BuilderTask` declares `professionalProviderUuid` (`UUID`).
- [x] `BuilderTask` declares `professionalVisitStartedAt` (`long`).
- [x] `BuilderTask` declares `rejectedProfessionalProviderUuid` (`UUID`).
- [x] `BuilderTask` declares `rejectedProfessionalItem` (`Item`).
- [x] `BuilderTask` declares `rejectedProfessionalUntil` (`long`).
- [x] `writeTask()` and `restoreTask()` reference fields with matching names and types.
- [x] `gradle.properties`, README, and GitHub Actions artifact use version 1.3.4.

## GitHub Actions build

Run `gradle clean build`. The five missing-field symbols from 1.3.3 must be gone. The `duplicate input class net/fabricmc/fabric/mixin/tag/package-info` message previously observed from the Fabric API dependency graph is a Loom duplicate-class warning, not the cause of the reported Java compilation failure; confirm the build proceeds beyond `compileJava`.

## In-game regression checks after a successful build

1. Place an employed Toolsmith within 40 blocks of an active builder and give it an available pickaxe offer. Confirm the builder selects it, walks to the provider and receives the item without payment.
2. Save and reload while the builder is travelling to a provider. Confirm the visit resumes or safely reselects a provider.
3. Make a provider unavailable or remove it during the visit. Confirm the builder rejects that provider temporarily and returns to normal material acquisition.
4. Fill the builder inventory so the provider output cannot be accepted. Confirm it does not loop indefinitely around the provider.
5. Exhaust an offer. Confirm no additional items are transferred until the provider restocks.
