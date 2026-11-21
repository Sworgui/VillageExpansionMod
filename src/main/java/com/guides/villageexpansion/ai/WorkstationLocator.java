package com.guides.villageexpansion.ai;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

/** Bounded lookup and placement helpers for reusable builder workstations. */
final class WorkstationLocator {
    private WorkstationLocator() { }

    static BlockPos findNearby(ServerWorld world, BlockPos origin, Block workstation,
                               int horizontalRadius, int verticalRadius) {
        if (world == null || origin == null || workstation == null) return null;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = Math.max(0, horizontalRadius);
        int yRadius = Math.max(0, verticalRadius);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -yRadius; dy <= yRadius; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (!isLoaded(world, candidate) || !world.getBlockState(candidate).isOf(workstation)) continue;
                    double distance = candidate.getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    static BlockPos findPlacement(ServerWorld world, VillagerEntity villager, int radius) {
        if (world == null || villager == null) return null;
        BlockPos origin = villager.getBlockPos();
        int safeRadius = Math.max(1, Math.min(6, radius));
        for (int ring = 1; ring <= safeRadius; ring++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = origin.add(dx, dy, dz);
                        BlockPos head = candidate.up();
                        BlockPos floor = candidate.down();
                        if (!isLoaded(world, candidate) || !isLoaded(world, head) || !isLoaded(world, floor)) continue;
                        BlockState feetState = world.getBlockState(candidate);
                        BlockState headState = world.getBlockState(head);
                        if (!(feetState.isAir() || feetState.isReplaceable())
                                || !(headState.isAir() || headState.isReplaceable())
                                || !world.getBlockState(floor).isSolidBlock(world, floor)
                                || !world.getFluidState(candidate).isEmpty()
                                || !world.getFluidState(head).isEmpty()
                                || feetState.hasBlockEntity() || headState.hasBlockEntity()) continue;
                        Box occupiedSpace = new Box(candidate.getX(), candidate.getY(), candidate.getZ(),
                                candidate.getX() + 1.0D, candidate.getY() + 2.0D, candidate.getZ() + 1.0D);
                        if (!world.getOtherEntities(villager, occupiedSpace, Entity::isAlive).isEmpty()) continue;
                        double distance = candidate.getSquaredDistance(origin);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = candidate.toImmutable();
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static boolean isLoaded(ServerWorld world, BlockPos pos) {
        return world.isChunkLoaded(new ChunkPos(pos).toLong());
    }
}
