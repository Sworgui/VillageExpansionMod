package com.guides.villageexpansion.mixin;

import com.guides.villageexpansion.ai.VillageBuilderController;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses only the vanilla Brain tick while a builder has the initiative.
 * Navigation, sensing, vanilla mob updates and entity movement continue to tick.
 * Danger and night conditions return control to the Brain for fleeing/sleeping.
 */
@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {
    @Redirect(
            method = "mobTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/ai/brain/Brain;tick(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/LivingEntity;)V"
            )
    )
    private void villageExpansion$prioritizeConstruction(
            Brain brain, ServerWorld world, LivingEntity entity) {
        VillagerEntity villager = (VillagerEntity) (Object) this;
        if (!VillageBuilderController.shouldSuppressVanillaBrain(villager)) {
            brain.tick(world, entity);
        }
    }
}
