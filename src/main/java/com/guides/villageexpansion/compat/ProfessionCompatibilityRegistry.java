package com.guides.villageexpansion.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.guides.villageexpansion.VillageExpansionMod;
import com.guides.villageexpansion.structures.VillageTemplateLibrary;
import com.guides.villageexpansion.structures.VillageTemplateLibrary.HouseTemplate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/** Optional, data-driven profession and building compatibility for other Fabric mods. */
public final class ProfessionCompatibilityRegistry {
    private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir()
            .resolve("village_expansion").resolve("compat");
    private static final List<ProfessionDefinition> DEFINITIONS = new ArrayList<>();
    private static boolean loaded;

    private ProfessionCompatibilityRegistry() {}

    public static synchronized void load() {
        DEFINITIONS.clear();
        try {
            Files.createDirectories(DIRECTORY);
            try (var files = Files.list(DIRECTORY)) {
                files.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(ProfessionCompatibilityRegistry::loadFile);
            }
            loaded = true;
            VillageExpansionMod.LOGGER.info("Loaded {} optional profession compatibility definitions from {}",
                    DEFINITIONS.size(), DIRECTORY);
        } catch (IOException exception) {
            VillageExpansionMod.LOGGER.error("Could not read profession compatibility directory {}", DIRECTORY, exception);
        }
    }

    private static void loadFile(Path file) {
        try (var reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            String modId = string(root, "modId", "");
            if (modId.isBlank()) {
                VillageExpansionMod.LOGGER.warn("Ignoring compatibility file {}: missing modId", file.getFileName());
                return;
            }
            JsonArray professions = root.has("professions") && root.get("professions").isJsonArray()
                    ? root.getAsJsonArray("professions") : new JsonArray();
            for (JsonElement element : professions) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                Identifier profession = Identifier.tryParse(string(entry, "profession", ""));
                Identifier workstation = Identifier.tryParse(string(entry, "workstation", ""));
                if (profession == null || workstation == null) {
                    VillageExpansionMod.LOGGER.warn("Ignoring invalid profession entry in {}", file.getFileName());
                    continue;
                }
                JsonObject structuresObject = entry.has("structures") && entry.get("structures").isJsonObject()
                        ? entry.getAsJsonObject("structures") : new JsonObject();
                Map<String, String> structures = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> structure : structuresObject.entrySet()) {
                    if (structure.getValue().isJsonPrimitive()) {
                        Identifier id = Identifier.tryParse(structure.getValue().getAsString());
                        if (id != null) structures.put(structure.getKey().toLowerCase(), id.toString());
                    }
                }
                if (structures.isEmpty()) {
                    VillageExpansionMod.LOGGER.warn("Ignoring {} in {}: no valid structures", profession, file.getFileName());
                    continue;
                }
                int weight = Math.max(1, Math.min(100, entry.has("weight") ? entry.get("weight").getAsInt() : 1));
                DEFINITIONS.add(new ProfessionDefinition(modId, profession, workstation, Map.copyOf(structures), weight));
            }
        } catch (Exception exception) {
            VillageExpansionMod.LOGGER.error("Could not load compatibility file {}", file.getFileName(), exception);
        }
    }

    /** Picks a configured third-party profession house for the current biome, if one is usable. */
    public static Optional<HouseTemplate> chooseHouse(ServerWorld world, BlockPos near, String villageStyle) {
        ensureLoaded();
        List<Candidate> candidates = new ArrayList<>();
        for (ProfessionDefinition definition : DEFINITIONS) {
            if (!FabricLoader.getInstance().isModLoaded(definition.modId())) continue;
            if (Registries.VILLAGER_PROFESSION.getOrEmpty(definition.profession()).isEmpty()) continue;
            Optional<Block> workstation = Registries.BLOCK.getOrEmpty(definition.workstation());
            if (workstation.isEmpty()) continue;
            String structureText = definition.structures().getOrDefault(villageStyle,
                    definition.structures().get("default"));
            if (structureText == null) continue;
            Identifier structureId = Identifier.tryParse(structureText);
            if (structureId == null) continue;
            Optional<HouseTemplate> loadedTemplate = VillageTemplateLibrary.load(world, structureId);
            if (loadedTemplate.isEmpty()) continue;
            HouseTemplate template = loadedTemplate.get();
            if (template.bedOffset() == null || template.workstationOffset() == null) continue;
            if (!definition.profession().equals(template.professionId())) continue;
            if (!template.hasUsefulHomeFeatures()) continue;
            candidates.add(new Candidate(template, definition.weight()));
        }
        if (candidates.isEmpty()) return Optional.empty();
        int totalWeight = candidates.stream().mapToInt(Candidate::weight).sum();
        int choice = new Random(world.getTime() ^ near.asLong()).nextInt(totalWeight);
        for (Candidate candidate : candidates) {
            choice -= candidate.weight();
            if (choice < 0) return Optional.of(candidate.template());
        }
        return Optional.of(candidates.get(0).template());
    }

    /** Resolve a custom profession from its workstation block, if this config declares one. */
    public static Optional<Identifier> professionForWorkstation(Block block) {
        ensureLoaded();
        Identifier blockId = Registries.BLOCK.getId(block);
        for (ProfessionDefinition definition : DEFINITIONS) {
            if (!FabricLoader.getInstance().isModLoaded(definition.modId())) continue;
            if (definition.workstation().equals(blockId)
                    && Registries.VILLAGER_PROFESSION.getOrEmpty(definition.profession()).isPresent()) {
                return Optional.of(definition.profession());
            }
        }
        return Optional.empty();
    }

    public static boolean isRegisteredWorkstation(Block block) {
        return professionForWorkstation(block).isPresent();
    }

    private static void ensureLoaded() {
        if (!loaded) load();
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private record Candidate(HouseTemplate template, int weight) {}

    private record ProfessionDefinition(String modId, Identifier profession, Identifier workstation,
                                        Map<String, String> structures, int weight) {}
}
