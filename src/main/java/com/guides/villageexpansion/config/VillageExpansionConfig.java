package com.guides.villageexpansion.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** User-editable server config. Values are loaded from config/village_expansion.json. */
public final class VillageExpansionConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("village_expansion.json");

    public static boolean enabled = true;
    public static int maxBuildersPerVillage = 3;
    public static double navigationSpeed = 0.65D;
    public static int taskTickInterval = 1;
    public static int assignmentScanInterval = 100;
    public static int retryDelayTicks = 600;
    public static int dangerRadius = 12;
    public static int dangerPauseTicks = 120;
    public static boolean sleepAtNightWhenHomeExists = true;
    public static int caveSearchRadius = 32;
    public static int caveSearchDepth = 48;
    public static int caveSearchIntervalTicks = 100;
    public static int mineMaxLength = 64;
    public static int mineSupportInterval = 4;
    public static int blockBreakBaseTicks = 28;
    public static int particleIntervalTicks = 20;
    public static int mineExclusionRadius = 24;
    public static int houseSpacing = 12;
    public static boolean blacksmithToolUpgrades = true;
    /** Legacy values retained for config migration; construction now derives exact blueprint requirements. */
    public static int woodRequired = 0;
    public static int stoneRequired = 0;
    public static int woodUnitsPerLog = 4;
    public static int woodSearchRadius = 32;
    public static boolean replantSaplings = true;
    public static boolean showBuilderTaskLabel = true;
    public static int saplingPlantRadius = 32;
    public static int saplingMinimumSpacing = 8;
    public static int materialSearchRadius = 28;
    public static int materialSearchIntervalTicks = 100;
    public static boolean useRecipeBookForCrafting = true;
    public static int maxPathSearchNodes = 8000;
    public static int buildBlockDelayTicks = 2;
    public static int navigationStallTimeoutTicks = 100;
    public static int navigationReassertIntervalTicks = 10;
    public static int navigationMaxRepaths = 4;
    public static int noResourceSearchLimit = 6;
    public static int houseSearchMinRadius = 12;
    public static int houseSearchMaxRadius = 44;
    public static int houseSearchStep = 4;
    public static int breedingScanIntervalTicks = 200;
    public static double breedingPartnerRange = 8.0D;
    public static int breedingParentCooldownTicks = 6000;
    public static int babyGrowthTicks = 24000;

    private VillageExpansionConfig() {}

    public static void load() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject json;
            if (Files.exists(FILE)) {
                try (var reader = Files.newBufferedReader(FILE)) {
                    json = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                json = new JsonObject();
            }

            enabled = bool(json, "enabled", true);
            maxBuildersPerVillage = clamp(integer(json, "maxBuildersPerVillage", 3), 1, 16);
            navigationSpeed = clamp(decimal(json, "navigationSpeed", 0.65D), 0.1D, 1.5D);
            taskTickInterval = clamp(integer(json, "taskTickInterval", 1), 1, 20);
            assignmentScanInterval = clamp(integer(json, "assignmentScanInterval", 100), 20, 1200);
            retryDelayTicks = clamp(integer(json, "retryDelayTicks", 600), 20, 12000);
            dangerRadius = clamp(integer(json, "dangerRadius", 12), 4, 48);
            dangerPauseTicks = clamp(integer(json, "dangerPauseTicks", 120), 20, 1200);
            sleepAtNightWhenHomeExists = bool(json, "sleepAtNightWhenHomeExists", true);
            caveSearchRadius = clamp(integer(json, "caveSearchRadius", 32), 8, 96);
            caveSearchDepth = clamp(integer(json, "caveSearchDepth", 48), 8, 96);
            caveSearchIntervalTicks = clamp(integer(json, "caveSearchIntervalTicks", 100), 20, 1200);
            mineMaxLength = clamp(integer(json, "mineMaxLength", 64), 8, 256);
            mineSupportInterval = clamp(integer(json, "mineSupportInterval", 4), 2, 16);
            blockBreakBaseTicks = clamp(integer(json, "blockBreakBaseTicks", 28), 4, 200);
            particleIntervalTicks = clamp(integer(json, "particleIntervalTicks", 20), 1, 200);
            mineExclusionRadius = clamp(integer(json, "mineExclusionRadius", 24), 8, 64);
            houseSpacing = clamp(integer(json, "houseSpacing", 12), 6, 32);
            blacksmithToolUpgrades = bool(json, "blacksmithToolUpgrades", true);
            woodRequired = 0; // Deprecated: exact requirements are derived from the selected structure.
            stoneRequired = 0; // Deprecated: exact requirements are derived from the selected structure.
            woodUnitsPerLog = clamp(integer(json, "woodUnitsPerLog", 4), 1, 8);
            woodSearchRadius = clamp(integer(json, "woodSearchRadius", 32), 8, 96);
            replantSaplings = bool(json, "replantSaplings", true);
            showBuilderTaskLabel = bool(json, "showBuilderTaskLabel", true);
            saplingPlantRadius = clamp(integer(json, "saplingPlantRadius", 32), 8, 64);
            saplingMinimumSpacing = clamp(integer(json, "saplingMinimumSpacing", 8), 4, 16);
            materialSearchRadius = clamp(integer(json, "materialSearchRadius", 28), 8, 96);
            materialSearchIntervalTicks = clamp(integer(json, "materialSearchIntervalTicks", 100), 20, 1200);
            useRecipeBookForCrafting = bool(json, "useRecipeBookForCrafting", true);
            maxPathSearchNodes = clamp(integer(json, "maxPathSearchNodes", 8000), 500, 50000);
            buildBlockDelayTicks = clamp(integer(json, "buildBlockDelayTicks", 2), 1, 40);
            navigationStallTimeoutTicks = clamp(integer(json, "navigationStallTimeoutTicks", 100), 40, 1200);
            navigationReassertIntervalTicks = clamp(integer(json, "navigationReassertIntervalTicks", 10), 1, 100);
            navigationMaxRepaths = clamp(integer(json, "navigationMaxRepaths", 4), 1, 12);
            noResourceSearchLimit = clamp(integer(json, "noResourceSearchLimit", 6), 1, 30);
            houseSearchMinRadius = clamp(integer(json, "houseSearchMinRadius", 12), 4, 64);
            houseSearchMaxRadius = clamp(integer(json, "houseSearchMaxRadius", 44), houseSearchMinRadius, 128);
            houseSearchStep = clamp(integer(json, "houseSearchStep", 4), 1, 16);
            breedingScanIntervalTicks = clamp(integer(json, "breedingScanIntervalTicks", 200), 20, 1200);
            breedingPartnerRange = clamp(decimal(json, "breedingPartnerRange", 8.0D), 2.0D, 32.0D);
            breedingParentCooldownTicks = clamp(integer(json, "breedingParentCooldownTicks", 6000), 600, 24000);
            babyGrowthTicks = clamp(integer(json, "babyGrowthTicks", 24000), 1200, 48000);

            // Write the complete effective config back so new options become visible after updates.
            Files.writeString(FILE, GSON.toJson(toJson()));
        } catch (Exception exception) {
            System.err.println("[Village Expansion] Could not load config; defaults will be used: " + exception.getMessage());
        }
    }

    private static JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("maxBuildersPerVillage", maxBuildersPerVillage);
        json.addProperty("navigationSpeed", navigationSpeed);
        json.addProperty("taskTickInterval", taskTickInterval);
        json.addProperty("assignmentScanInterval", assignmentScanInterval);
        json.addProperty("retryDelayTicks", retryDelayTicks);
        json.addProperty("dangerRadius", dangerRadius);
        json.addProperty("dangerPauseTicks", dangerPauseTicks);
        json.addProperty("sleepAtNightWhenHomeExists", sleepAtNightWhenHomeExists);
        json.addProperty("caveSearchRadius", caveSearchRadius);
        json.addProperty("caveSearchDepth", caveSearchDepth);
        json.addProperty("caveSearchIntervalTicks", caveSearchIntervalTicks);
        json.addProperty("mineMaxLength", mineMaxLength);
        json.addProperty("mineSupportInterval", mineSupportInterval);
        json.addProperty("blockBreakBaseTicks", blockBreakBaseTicks);
        json.addProperty("particleIntervalTicks", particleIntervalTicks);
        json.addProperty("mineExclusionRadius", mineExclusionRadius);
        json.addProperty("houseSpacing", houseSpacing);
        json.addProperty("blacksmithToolUpgrades", blacksmithToolUpgrades);
        json.addProperty("woodRequired", woodRequired);
        json.addProperty("stoneRequired", stoneRequired);
        json.addProperty("woodUnitsPerLog", woodUnitsPerLog);
        json.addProperty("woodSearchRadius", woodSearchRadius);
        json.addProperty("replantSaplings", replantSaplings);
        json.addProperty("showBuilderTaskLabel", showBuilderTaskLabel);
        json.addProperty("saplingPlantRadius", saplingPlantRadius);
        json.addProperty("saplingMinimumSpacing", saplingMinimumSpacing);
        json.addProperty("materialSearchRadius", materialSearchRadius);
        json.addProperty("materialSearchIntervalTicks", materialSearchIntervalTicks);
        json.addProperty("useRecipeBookForCrafting", useRecipeBookForCrafting);
        json.addProperty("maxPathSearchNodes", maxPathSearchNodes);
        json.addProperty("buildBlockDelayTicks", buildBlockDelayTicks);
        json.addProperty("navigationStallTimeoutTicks", navigationStallTimeoutTicks);
        json.addProperty("navigationReassertIntervalTicks", navigationReassertIntervalTicks);
        json.addProperty("navigationMaxRepaths", navigationMaxRepaths);
        json.addProperty("noResourceSearchLimit", noResourceSearchLimit);
        json.addProperty("houseSearchMinRadius", houseSearchMinRadius);
        json.addProperty("houseSearchMaxRadius", houseSearchMaxRadius);
        json.addProperty("houseSearchStep", houseSearchStep);
        json.addProperty("breedingScanIntervalTicks", breedingScanIntervalTicks);
        json.addProperty("breedingPartnerRange", breedingPartnerRange);
        json.addProperty("breedingParentCooldownTicks", breedingParentCooldownTicks);
        json.addProperty("babyGrowthTicks", babyGrowthTicks);
        return json;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) ? json.get(key).getAsBoolean() : fallback;
    }
    private static int integer(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }
    private static double decimal(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
