package com.guides.villageexpansion;

import com.guides.villageexpansion.ai.VillageBreedingController;
import com.guides.villageexpansion.ai.VillageBuilderController;
import com.guides.villageexpansion.config.VillageExpansionConfig;
import com.guides.villageexpansion.compat.ProfessionCompatibilityRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VillageExpansionMod implements ModInitializer {
    public static final String MOD_ID = "village_expansion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Load server behavior settings before any worker is scheduled.
        VillageExpansionConfig.load();
        ProfessionCompatibilityRegistry.load();
        // Forces registration before any villager is assigned the temporary profession.
        BuilderProfession.register();
        ServerEntityEvents.ENTITY_LOAD.register(VillageBuilderController::onVillagerEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(VillageBuilderController::onVillagerEntityUnload);
        ServerTickEvents.END_WORLD_TICK.register(VillageBuilderController::tickWorld);
        ServerTickEvents.END_WORLD_TICK.register(VillageBreedingController::tickWorld);
        LOGGER.info("Village Expansion 1.4.1 initialized: house-first construction, compact site search and adaptive terrain clearing enabled.");
    }
}
