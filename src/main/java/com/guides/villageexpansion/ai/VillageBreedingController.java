package com.guides.villageexpansion.ai;

import com.guides.villageexpansion.BuilderProfession;
import com.guides.villageexpansion.config.VillageExpansionConfig;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Adds a bed-independent fallback for villager breeding.
 * The vanilla food/readiness checks are retained; only the free-bed check is bypassed.
 */
public final class VillageBreedingController {
    private VillageBreedingController() {
    }

    public static void tickWorld(ServerWorld world) {
        if (world.getTime() % VillageExpansionConfig.breedingScanIntervalTicks != 0L) {
            return;
        }

        Set<UUID> paired = new HashSet<>();
        List<VillagerEntity> loadedVillagers = new ArrayList<>();
        for (VillagerEntity villager : VillageBuilderController.getLoadedVillagers(world)) {
            if (!villager.isBaby()) loadedVillagers.add(villager);
        }

        for (VillagerEntity villager : loadedVillagers) {
            if (paired.contains(villager.getUuid()) || !isReadyPartner(villager)) {
                continue;
            }

            Box searchBox = villager.getBoundingBox().expand(VillageExpansionConfig.breedingPartnerRange);
            VillagerEntity partner = world.getEntitiesByClass(
                            VillagerEntity.class,
                            searchBox,
                            candidate -> candidate != villager
                                    && !paired.contains(candidate.getUuid())
                                    && isReadyPartner(candidate)
                                    && candidate.squaredDistanceTo(villager.getX(), villager.getY(), villager.getZ())
                                            <= VillageExpansionConfig.breedingPartnerRange * VillageExpansionConfig.breedingPartnerRange)
                    .stream()
                    .min((a, b) -> Double.compare(
                            a.squaredDistanceTo(villager.getX(), villager.getY(), villager.getZ()),
                            b.squaredDistanceTo(villager.getX(), villager.getY(), villager.getZ())))
                    .orElse(null);

            if (partner == null) {
                continue;
            }

            VillagerEntity baby = villager.createChild(world, partner);
            if (baby == null) {
                continue;
            }

            // Spawn the baby beside the first parent. Consume food only after the entity is accepted.
            double babyX = villager.getX();
            double babyY = villager.getY();
            double babyZ = villager.getZ();
            baby.refreshPositionAndAngles(babyX, babyY, babyZ, villager.getYaw(), 0.0F);
            baby.setBreedingAge(-VillageExpansionConfig.babyGrowthTicks);
            if (!world.spawnEntity(baby)) {
                continue;
            }

            // Keep vanilla food consumption and cooldowns, but do not require a reserved bed.
            villager.eatForBreeding();
            partner.eatForBreeding();
            villager.setBreedingAge(VillageExpansionConfig.breedingParentCooldownTicks);
            partner.setBreedingAge(VillageExpansionConfig.breedingParentCooldownTicks);
            world.spawnParticles(ParticleTypes.HEART,
                    babyX, babyY + 0.8D, babyZ,
                    7, 0.45D, 0.35D, 0.45D, 0.02D);

            paired.add(villager.getUuid());
            paired.add(partner.getUuid());
        }
    }

    private static boolean isReadyPartner(VillagerEntity villager) {
        if (!villager.isAlive() || villager.isBaby() || villager.hasCustomer()) {
            return false;
        }
        if (villager.getVillagerData().getProfession().equals(BuilderProfession.BUILDER)) {
            return false;
        }
        if (!villager.isReadyToBreed() || !villager.canBreed() || !villager.wantsToStartBreeding()) {
            return false;
        }
        // Let an already-running vanilla breeding interaction finish first.
        return villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.BREED_TARGET).isEmpty();
    }
}
