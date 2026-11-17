package com.guides.villageexpansion;

import com.guides.villageexpansion.ai.VillageBreedingController;
import com.guides.villageexpansion.ai.VillageBuilderController;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VillageExpansionMod implements ModInitializer {
    public static final String MOD_ID = "village_expansion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Forces registration before any villager is assigned the temporary profession.
        BuilderProfession.register();
        ServerTickEvents.END_WORLD_TICK.register(VillageBuilderController::tickWorld);
        ServerTickEvents.END_WORLD_TICK.register(VillageBreedingController::tickWorld);
        LOGGER.info("Village Expansion initialized: village construction, roads and bed-independent breeding enabled.");
    }
}
