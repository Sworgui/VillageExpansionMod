package com.guides.villageexpansion.mixin;

import com.guides.villageexpansion.ai.VillageBuilderController;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the full vanilla Brain alive, then gives an active construction task the final navigation priority. */
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
        brain.tick(world, entity);
        if (entity instanceof VillagerEntity villager) {
            VillageBuilderController.afterVanillaBrainTick(villager);
        }
    }
}
