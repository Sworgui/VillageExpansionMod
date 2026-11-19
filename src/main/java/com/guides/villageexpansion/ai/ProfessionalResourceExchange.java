package com.guides.villageexpansion.ai;

import com.guides.villageexpansion.BuilderProfession;
import com.guides.villageexpansion.config.VillageExpansionConfig;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.VillagerProfession;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Free, stock-limited supply sharing between employed villagers and builders.
 * This deliberately does not execute Merchant.trade(): the builder pays no
 * currency or barter ingredients and the professional receives no trade XP.
 * The offer use counter is advanced so the same stock cannot be duplicated;
 * vanilla villager restocking makes the supply available again later.
 */
final class ProfessionalResourceExchange {
    private static long cacheWindow = Long.MIN_VALUE;
    private static final Map<String, UUID> POSITIVE_CACHE = new HashMap<>();
    private static final Set<String> NEGATIVE_CACHE = new HashSet<>();

    private ProfessionalResourceExchange() { }

    static VillagerEntity findNearestProvider(ServerWorld world, VillagerEntity builder, Item wanted) {
        return findNearestProvider(world, builder, wanted, null);
    }

    static VillagerEntity findNearestProvider(ServerWorld world, VillagerEntity builder, Item wanted,
                                              UUID excludedProvider) {
        if (!VillageExpansionConfig.professionalResourceSharing || wanted == null) return null;
        int radius = VillageExpansionConfig.professionalResourceSearchRadius;
        String key = cacheKey(world, builder, wanted, excludedProvider);
        UUID cachedId = POSITIVE_CACHE.get(key);
        if (cachedId != null && !cachedId.equals(excludedProvider)) {
            VillagerEntity cached = resolveProvider(world, builder, wanted, cachedId);
            if (cached != null) return cached;
            POSITIVE_CACHE.remove(key);
        }
        if (NEGATIVE_CACHE.contains(key)) return null;

        Box searchBox = builder.getBoundingBox().expand(radius);
        VillagerEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        double maxDistanceSquared = (double) radius * radius;
        for (VillagerEntity candidate : world.getEntitiesByClass(VillagerEntity.class, searchBox,
                entity -> isEligibleProvider(entity, builder))) {
            if (candidate.getUuid().equals(excludedProvider)) continue;
            double distanceSquared = builder.squaredDistanceTo(candidate);
            if (distanceSquared > maxDistanceSquared || distanceSquared >= bestDistance) continue;
            if (!hasAvailableOffer(candidate, wanted)) continue;
            best = candidate;
            bestDistance = distanceSquared;
        }

        if (best == null) NEGATIVE_CACHE.add(key);
        else POSITIVE_CACHE.put(key, best.getUuid());
        return best;
    }

    static boolean hasAvailableOffer(VillagerEntity provider, Item wanted) {
        if (provider == null || wanted == null || provider.isRemoved() || !provider.isAlive()
                || provider.isBaby() || provider.isAiDisabled() || provider.hasCustomer()) return false;
        VillagerProfession profession = provider.getVillagerData().getProfession();
        if (profession.equals(VillagerProfession.NONE) || profession.equals(VillagerProfession.NITWIT)
                || profession.equals(BuilderProfession.BUILDER)) return false;
        for (TradeOffer offer : provider.getOffers()) {
            if (offer.isDisabled()) continue;
            ItemStack result = offer.getSellItem();
            if (!result.isEmpty() && MaterialAcquisitionPlanner.isCompatibleProviderItem(result.getItem(), wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Transfers only the missing amount and consumes offer capacity, never payment items. */
    static int transferFreeSupplies(ServerWorld world, VillagerEntity builder, VillagerEntity provider,
                                    Item wanted, int requestedTotal) {
        if (builder == null || provider == null || wanted == null || requestedTotal <= 0
                || !isEligibleProvider(provider, builder)) return 0;

        int alreadyHave = countCompatibleItems(builder, wanted);
        int missing = Math.max(0, requestedTotal - alreadyHave);
        int delivered = 0;
        if (missing <= 0) return 0;

        for (TradeOffer offer : provider.getOffers()) {
            if (missing <= 0) break;
            if (offer.isDisabled()) continue;
            ItemStack offerOutput = offer.copySellItem();
            if (offerOutput.isEmpty()
                    || !MaterialAcquisitionPlanner.isCompatibleProviderItem(offerOutput.getItem(), wanted)) {
                continue;
            }

            // One offer may have several available uses. Transfer one normal
            // output stack per reserved use, stopping as soon as the builder
            // has enough or the professional's stock is exhausted.
            while (missing > 0 && !offer.isDisabled()) {
                ItemStack offered = offer.copySellItem();
                int giveCount = Math.min(missing, offered.getCount());
                offered.setCount(giveCount);
                ItemStack remainder = builder.getInventory().addStack(offered);
                int accepted = giveCount - remainder.getCount();
                if (accepted <= 0) break;

                // Reserve one stock use only after at least one item was accepted.
                // Buy stacks are never read or consumed and Merchant.trade() is not called.
                offer.use();
                builder.getInventory().markDirty();
                if (!remainder.isEmpty()) {
                    world.spawnEntity(new ItemEntity(world, builder.getX(), builder.getY() + 0.35D,
                            builder.getZ(), remainder));
                }
                delivered += accepted;
                missing -= accepted;
            }
        }

        if (delivered > 0) invalidate(world, builder, wanted);
        return delivered;
    }

    static double estimateCost(ServerWorld world, VillagerEntity builder, Item wanted, int amount) {
        VillagerEntity provider = findNearestProvider(world, builder, wanted);
        if (provider == null) return Double.POSITIVE_INFINITY;
        // Providers should beat lengthy self-extraction/crafting, while closer
        // professionals are marginally preferred over those farther away.
        double distance = Math.sqrt(builder.squaredDistanceTo(provider));
        return 0.35D + distance / Math.max(1.0D, VillageExpansionConfig.professionalResourceSearchRadius);
    }

    private static int countCompatibleItems(VillagerEntity builder, Item wanted) {
        int count = 0;
        for (int slot = 0; slot < builder.getInventory().size(); slot++) {
            ItemStack stack = builder.getInventory().getStack(slot);
            if (!stack.isEmpty() && MaterialAcquisitionPlanner.isCompatibleProviderItem(stack.getItem(), wanted)) {
                count += stack.getCount();
            }
        }
        ItemStack held = builder.getEquippedStack(net.minecraft.entity.EquipmentSlot.MAINHAND);
        if (!held.isEmpty() && MaterialAcquisitionPlanner.isCompatibleProviderItem(held.getItem(), wanted)) {
            count += held.getCount();
        }
        return count;
    }

    private static VillagerEntity resolveProvider(ServerWorld world, VillagerEntity builder, Item wanted, UUID id) {
        if (!(world.getEntity(id) instanceof VillagerEntity provider)) return null;
        if (!isEligibleProvider(provider, builder) || !hasAvailableOffer(provider, wanted)) return null;
        double radius = VillageExpansionConfig.professionalResourceSearchRadius;
        return builder.squaredDistanceTo(provider) <= radius * radius ? provider : null;
    }

    private static boolean isEligibleProvider(VillagerEntity candidate, VillagerEntity builder) {
        if (candidate == null || candidate == builder || !candidate.isAlive() || candidate.isRemoved()
                || candidate.isBaby() || candidate.isAiDisabled() || candidate.hasCustomer()) return false;
        VillagerProfession profession = candidate.getVillagerData().getProfession();
        return !profession.equals(VillagerProfession.NONE)
                && !profession.equals(VillagerProfession.NITWIT)
                && !profession.equals(BuilderProfession.BUILDER);
    }

    private static String cacheKey(ServerWorld world, VillagerEntity builder, Item wanted, UUID excludedProvider) {
        long window = world.getTime() / 20L;
        if (window != cacheWindow) {
            cacheWindow = window;
            POSITIVE_CACHE.clear();
            NEGATIVE_CACHE.clear();
        }
        return world.getRegistryKey().getValue() + ":" + builder.getUuid() + ":"
                + net.minecraft.registry.Registries.ITEM.getId(wanted) + ":"
                + (excludedProvider == null ? "none" : excludedProvider);
    }

    private static void invalidate(ServerWorld world, VillagerEntity builder, Item wanted) {
        String key = cacheKey(world, builder, wanted, null);
        POSITIVE_CACHE.remove(key);
        NEGATIVE_CACHE.remove(key);
    }
}
