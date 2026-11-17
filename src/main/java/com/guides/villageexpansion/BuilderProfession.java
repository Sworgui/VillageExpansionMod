package com.guides.villageexpansion;

import com.google.common.collect.ImmutableSet;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;
import net.minecraft.sound.SoundEvents;

/**
 * Temporary profession used only while a villager is actively building.
 * It has no workstation predicates, so it cannot claim a vanilla job site.
 */
public final class BuilderProfession {
    public static final VillagerProfession BUILDER = Registry.register(
            Registries.VILLAGER_PROFESSION,
            Identifier.of(VillageExpansionMod.MOD_ID, "builder"),
            new VillagerProfession(
                    "builder",
                    entry -> false,
                    entry -> false,
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    SoundEvents.BLOCK_WOOD_PLACE
            )
    );

    private BuilderProfession() {
    }

    public static void register() {
        // Invoking this method forces the static registry entry to be initialized.
    }
}
