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
    public static int maxBuildersPerVillage = 20;
    /** Hard per-dimension safety cap, including unloaded saved construction tasks. */
    public static int maxActiveBuildersPerDimension = 20;
    public static double navigationSpeed = 0.65D;
    public static int taskTickInterval = 1;
    /** Maximum expensive construction task updates performed per world tick. */
    public static int builderTaskUpdatesPerTick = 1;
    /** Global spacing between expensive custom builder updates; vanilla movement still ticks every tick. */
    public static int builderTaskGlobalIntervalTicks = 1;
    /** Minimum spacing between updates of the same builder; vanilla Brain/navigation still ticks normally. */
    public static int builderTaskMinIntervalTicks = 10;
    /** Hostile-entity queries are cached; direct attacker/health checks remain immediate. */
    public static int dangerCheckIntervalTicks = 100;
    public static int assignmentScanInterval = 40;
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
    public static int particleIntervalTicks = 100;
    /** Optional builder particle feedback; disabled by default for low-end servers. */
    public static boolean builderParticlesEnabled = false;
    /** Minimum number of game ticks between expensive world-resource searches in one dimension. */
    public static int spatialSearchIntervalTicks = 20;
    public static int mineExclusionRadius = 24;
    /** Failed stone/cobblestone scans before a builder starts a dedicated stair mine. */
    public static int stoneSearchFailuresBeforeMine = 5;
    /** Minimum horizontal distance between a village centre and a registered mine entrance. */
    public static int mineMinimumDistanceFromVillage = 20;
    public static int houseSpacing = 12;
    /** Legacy option retained for existing config files; superseded by free professional supply sharing. */
    @Deprecated public static boolean blacksmithToolUpgrades = true;
    public static boolean professionalResourceSharing = true;
    public static int professionalResourceSearchRadius = 40;
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
    // Lazy planner advances only one bounded planning slice globally per controller tick.
    public static int lazyPlanningStepIntervalTicks = 40;
    public static int lazyPlanningRecipeEntriesPerTick = 2;
    public static boolean useRecipeBookForCrafting = true;
    public static int maxPathSearchNodes = 300;
    public static int buildBlockDelayTicks = 2;
    public static int navigationStallTimeoutTicks = 100;
    public static int navigationReassertIntervalTicks = 40;
    public static int navigationMaxRepaths = 4;
    public static int noResourceSearchLimit = 6;
    public static int houseSearchMinRadius = 4;
    public static int houseSearchMaxRadius = 12;
    public static int houseSearchStep = 2;
    /** Maximum natural terrain height spread accepted for a house plot before adaptive leveling. */
    public static int maxSiteTerrainVariation = 6;
    /** Maximum number of block cut/fill operations allowed when adapting a house plot. */
    public static int maxSiteTerraformBlocks = 160;
    public static int breedingScanIntervalTicks = 1200;
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

            // One-time performance migrations. Version 3 applies the conservative
            // global work budget required for large villages; users can still tune it later.
            int performanceTuningVersion = integer(json, "performanceTuningVersion", 0);
            if (performanceTuningVersion < 1) {
                json.addProperty("maxBuildersPerVillage", 20);
                json.addProperty("builderTaskUpdatesPerTick", 1);
                json.addProperty("builderTaskMinIntervalTicks", 10);
                json.addProperty("dangerCheckIntervalTicks", 40);
                json.addProperty("lazyPlanningStepIntervalTicks", 4);
                json.addProperty("lazyPlanningRecipeEntriesPerTick", 8);
                json.addProperty("maxPathSearchNodes", 1200);
                json.addProperty("breedingScanIntervalTicks", 1200);
                json.addProperty("builderParticlesEnabled", false);
                json.addProperty("spatialSearchIntervalTicks", 4);
                performanceTuningVersion = 1;
                json.addProperty("performanceTuningVersion", performanceTuningVersion);
            }
            if (performanceTuningVersion < 2) {
                json.addProperty("builderTaskGlobalIntervalTicks", 2);
                json.addProperty("builderTaskMinIntervalTicks", 20);
                json.addProperty("spatialSearchIntervalTicks", 10);
                json.addProperty("lazyPlanningStepIntervalTicks", 20);
                json.addProperty("lazyPlanningRecipeEntriesPerTick", 4);
                json.addProperty("maxPathSearchNodes", 600);
                json.addProperty("assignmentScanInterval", 10);
                json.addProperty("dangerCheckIntervalTicks", 60);
                performanceTuningVersion = 2;
                json.addProperty("performanceTuningVersion", performanceTuningVersion);
            }
            if (performanceTuningVersion < 3) {
                // One custom builder update globally per tick, round-robin across all
                // active builders. With 20 builders each gets one action slice/sec.
                json.addProperty("builderTaskUpdatesPerTick", 1);
                json.addProperty("builderTaskGlobalIntervalTicks", 1);
                json.addProperty("builderTaskMinIntervalTicks", 20);
                json.addProperty("spatialSearchIntervalTicks", 8);
                json.addProperty("lazyPlanningStepIntervalTicks", 20);
                json.addProperty("lazyPlanningRecipeEntriesPerTick", 4);
                json.addProperty("assignmentScanInterval", 10);
                json.addProperty("dangerCheckIntervalTicks", 60);
                json.addProperty("maxPathSearchNodes", 600);
                performanceTuningVersion = 3;
                json.addProperty("performanceTuningVersion", performanceTuningVersion);
            }

            if (performanceTuningVersion < 4) {
                // Loaded villager caches are now maintained by Fabric entity lifecycle events.
                // The old polling interval is obsolete; only a one-time entity-free initialization remains.
                json.remove("entityDiscoveryIntervalTicks");
                json.addProperty("assignmentScanInterval", 10);
                performanceTuningVersion = 4;
                json.addProperty("performanceTuningVersion", performanceTuningVersion);
            }
            if (performanceTuningVersion < 5) {
                // Ultra-low-overhead defaults: keep vanilla Brain/navigation ticking,
                // but run the custom construction state machine in a small global budget.
                json.addProperty("maxActiveBuildersPerDimension", 20);
                json.addProperty("taskTickInterval", 2);
                json.addProperty("builderTaskUpdatesPerTick", 1);
                json.addProperty("builderTaskGlobalIntervalTicks", 2);
                json.addProperty("builderTaskMinIntervalTicks", 40);
                json.addProperty("assignmentScanInterval", 40);
                json.addProperty("dangerCheckIntervalTicks", 100);
                json.addProperty("spatialSearchIntervalTicks", 20);
                json.addProperty("lazyPlanningStepIntervalTicks", 40);
                json.addProperty("lazyPlanningRecipeEntriesPerTick", 2);
                json.addProperty("maxPathSearchNodes", 300);
                json.addProperty("navigationReassertIntervalTicks", 40);
                performanceTuningVersion = 5;
                json.addProperty("performanceTuningVersion", performanceTuningVersion);
            }
            if (performanceTuningVersion < 6) {
                // Keep new homes close to their village and allow controlled terraforming
                // instead of requiring a naturally flat, empty footprint.
                json.addProperty("houseSearchMinRadius", 4);
                json.addProperty("houseSearchMaxRadius", 18);
                json.addProperty("houseSearchStep", 3);
                json.addProperty("maxSiteTerrainVariation", 4);
                json.addProperty("maxSiteTerraformBlocks", 96);
                performanceTuningVersion = 6;
                json.addProperty("performanceTuningVersion", performanceTuningVersion);
            }
            if (performanceTuningVersion < 7) {
                // Search a compact radius, then adapt moderate slopes instead of
                // roaming farther for a naturally perfect, flat clearing.
                json.addProperty("houseSearchMinRadius", 4);
                json.addProperty("houseSearchMaxRadius", 12);
                json.addProperty("houseSearchStep", 2);
                json.addProperty("maxSiteTerrainVariation", 6);
                json.addProperty("maxSiteTerraformBlocks", 160);
                performanceTuningVersion = 7;
                json.addProperty("performanceTuningVersion", performanceTuningVersion);
            }
            if (performanceTuningVersion < 8) {
                // Mining progress is now tick-accurate. Make the surrounding task
                // state machine react faster to broken tools and failed destinations.
                json.addProperty("taskTickInterval", 1);
                json.addProperty("builderTaskGlobalIntervalTicks", 1);
                json.addProperty("builderTaskMinIntervalTicks", 10);
                performanceTuningVersion = 8;
                json.addProperty("performanceTuningVersion", performanceTuningVersion);
            }

            enabled = bool(json, "enabled", true);
            maxBuildersPerVillage = clamp(integer(json, "maxBuildersPerVillage", 20), 1, 20);
            maxActiveBuildersPerDimension = clamp(integer(json, "maxActiveBuildersPerDimension", 20), 1, 20);
            navigationSpeed = clamp(decimal(json, "navigationSpeed", 0.65D), 0.1D, 1.5D);
            taskTickInterval = clamp(integer(json, "taskTickInterval", 1), 1, 20);
            assignmentScanInterval = clamp(integer(json, "assignmentScanInterval", 40), 20, 1200);
            builderTaskUpdatesPerTick = clamp(integer(json, "builderTaskUpdatesPerTick", 1), 1, 2);
            builderTaskGlobalIntervalTicks = clamp(integer(json, "builderTaskGlobalIntervalTicks", 1), 1, 20);
            builderTaskMinIntervalTicks = clamp(integer(json, "builderTaskMinIntervalTicks", 10), 10, 200);
            dangerCheckIntervalTicks = clamp(integer(json, "dangerCheckIntervalTicks", 100), 20, 400);
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
            particleIntervalTicks = clamp(integer(json, "particleIntervalTicks", 100), 20, 1200);
            builderParticlesEnabled = bool(json, "builderParticlesEnabled", false);
            spatialSearchIntervalTicks = clamp(integer(json, "spatialSearchIntervalTicks", 20), 4, 200);
            mineExclusionRadius = clamp(integer(json, "mineExclusionRadius", 24), 8, 64);
            stoneSearchFailuresBeforeMine = clamp(integer(json, "stoneSearchFailuresBeforeMine", 5), 1, 20);
            // Keep the requested 20-block safety offset as a hard minimum.
            mineMinimumDistanceFromVillage = clamp(integer(json, "mineMinimumDistanceFromVillage", 20), 20, 96);
            houseSpacing = clamp(integer(json, "houseSpacing", 12), 6, 32);
            blacksmithToolUpgrades = bool(json, "blacksmithToolUpgrades", true); // Legacy, no longer consumes builder ingots.
            professionalResourceSharing = bool(json, "professionalResourceSharing", true);
            professionalResourceSearchRadius = clamp(integer(json, "professionalResourceSearchRadius", 40), 8, 128);
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
            lazyPlanningStepIntervalTicks = clamp(integer(json, "lazyPlanningStepIntervalTicks", 40), 10, 200);
            lazyPlanningRecipeEntriesPerTick = clamp(integer(json, "lazyPlanningRecipeEntriesPerTick", 2), 1, 32);
            useRecipeBookForCrafting = bool(json, "useRecipeBookForCrafting", true);
            maxPathSearchNodes = clamp(integer(json, "maxPathSearchNodes", 300), 100, 600);
            buildBlockDelayTicks = clamp(integer(json, "buildBlockDelayTicks", 2), 1, 40);
            navigationStallTimeoutTicks = clamp(integer(json, "navigationStallTimeoutTicks", 100), 40, 1200);
            navigationReassertIntervalTicks = clamp(integer(json, "navigationReassertIntervalTicks", 40), 10, 200);
            navigationMaxRepaths = clamp(integer(json, "navigationMaxRepaths", 4), 1, 12);
            noResourceSearchLimit = clamp(integer(json, "noResourceSearchLimit", 6), 1, 30);
            houseSearchMinRadius = clamp(integer(json, "houseSearchMinRadius", 4), 2, 32);
            houseSearchMaxRadius = clamp(integer(json, "houseSearchMaxRadius", 12), houseSearchMinRadius, 32);
            houseSearchStep = clamp(integer(json, "houseSearchStep", 2), 2, 8);
            maxSiteTerrainVariation = clamp(integer(json, "maxSiteTerrainVariation", 6), 1, 8);
            maxSiteTerraformBlocks = clamp(integer(json, "maxSiteTerraformBlocks", 160), 8, 192);
            breedingScanIntervalTicks = clamp(integer(json, "breedingScanIntervalTicks", 1200), 200, 12000);
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
        json.addProperty("maxActiveBuildersPerDimension", maxActiveBuildersPerDimension);
        json.addProperty("navigationSpeed", navigationSpeed);
        json.addProperty("taskTickInterval", taskTickInterval);
        json.addProperty("builderTaskUpdatesPerTick", builderTaskUpdatesPerTick);
        json.addProperty("builderTaskGlobalIntervalTicks", builderTaskGlobalIntervalTicks);
        json.addProperty("builderTaskMinIntervalTicks", builderTaskMinIntervalTicks);
        json.addProperty("dangerCheckIntervalTicks", dangerCheckIntervalTicks);
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
        json.addProperty("builderParticlesEnabled", builderParticlesEnabled);
        json.addProperty("spatialSearchIntervalTicks", spatialSearchIntervalTicks);
        json.addProperty("performanceTuningVersion", 8);
        json.addProperty("mineExclusionRadius", mineExclusionRadius);
        json.addProperty("stoneSearchFailuresBeforeMine", stoneSearchFailuresBeforeMine);
        json.addProperty("mineMinimumDistanceFromVillage", mineMinimumDistanceFromVillage);
        json.addProperty("houseSpacing", houseSpacing);
        json.addProperty("blacksmithToolUpgrades", blacksmithToolUpgrades);
        json.addProperty("professionalResourceSharing", professionalResourceSharing);
        json.addProperty("professionalResourceSearchRadius", professionalResourceSearchRadius);
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
        json.addProperty("lazyPlanningStepIntervalTicks", lazyPlanningStepIntervalTicks);
        json.addProperty("lazyPlanningRecipeEntriesPerTick", lazyPlanningRecipeEntriesPerTick);
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
        json.addProperty("maxSiteTerrainVariation", maxSiteTerrainVariation);
        json.addProperty("maxSiteTerraformBlocks", maxSiteTerraformBlocks);
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
