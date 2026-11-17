package com.guides.villageexpansion.ai;

import com.guides.villageexpansion.VillageExpansionMod;
import com.guides.villageexpansion.BuilderProfession;
import com.guides.villageexpansion.data.VillageExpansionState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.particle.ParticleTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side village worker controller.
 *
 * This is intentionally separate from the vanilla Brain task graph. It uses
 * the villager's existing navigation to move between work locations and only
 * advances a project while the villager is loaded in a server world.
 */
public final class VillageBuilderController {
    private static final int TICK_STEP = 10;
    private static final int ASSIGNMENT_SCAN_INTERVAL = 100;
    private static final int RETRY_DELAY = 600;
    private static final int WOOD_REQUIRED = 88;   // 22 logs converted into plank-equivalent building units
    private static final int STONE_REQUIRED = 16;  // one stone-tool-material item per foundation block
    private static final double NAV_SPEED = 0.82D;
    private static final String ORIGINAL_PROFESSION_TAG_PREFIX = "village_expansion_original_profession=";

    private static final Map<UUID, BuilderTask> ACTIVE_TASKS = new HashMap<>();
    private static final Map<UUID, Long> RETRY_AT = new HashMap<>();

    private VillageBuilderController() {
    }

    public static void tickWorld(ServerWorld world) {
        if (world.getTime() % TICK_STEP != 0L) {
            return;
        }

        for (Entity entity : world.iterateEntities()) {
            if (!(entity instanceof VillagerEntity villager)) {
                continue;
            }

            UUID uuid = villager.getUuid();
            BuilderTask task = ACTIVE_TASKS.get(uuid);
            if (task != null) {
                if (!villager.isAlive() || villager.isRemoved() || !task.dimension.equals(world.getRegistryKey())) {
                    abandonTask(villager, task);
                    continue;
                }
                tickTask(world, villager, task);
                continue;
            }

            restoreOrphanedBuilderProfession(villager);
            if (world.getTime() % ASSIGNMENT_SCAN_INTERVAL == 0L) {
                tryAssignBuilder(world, villager);
            }
        }
    }

    private static void tryAssignBuilder(ServerWorld world, VillagerEntity villager) {
        if (!villager.isAlive() || villager.isBaby() || villager.isAiDisabled() || villager.hasCustomer()) {
            return;
        }
        if (villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME).isPresent()) {
            return;
        }
        if (world.getTime() < RETRY_AT.getOrDefault(villager.getUuid(), 0L)) {
            return;
        }

        BlockPos anchor = findVillageAnchor(world, villager);
        if (anchor == null) {
            RETRY_AT.put(villager.getUuid(), world.getTime() + RETRY_DELAY);
            return;
        }

        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE,
                VillageExpansionState.STORAGE_ID
        );
        VillageExpansionState.VillageRecord village = saved.findOrCreateVillage(anchor);

        if (hasActiveBuilder(world, village.id())) {
            RETRY_AT.put(villager.getUuid(), world.getTime() + 200L);
            return;
        }

        BlockPos site = findBuildSite(world, village);
        if (site == null) {
            RETRY_AT.put(villager.getUuid(), world.getTime() + RETRY_DELAY);
            return;
        }

        boolean professional = village.nextHouseIsProfessional();
        BuilderTask task = new BuilderTask(world.getRegistryKey(), village.id(), site, professional,
                villager.getEquippedStack(EquipmentSlot.MAINHAND).copy(), villager.getVillagerData());
        task.pathBlocks.addAll(createVillagePath(world, village.center(), site));
        task.treeTargets.addAll(findTreesAlongPath(world, task.pathBlocks, site));
        task.levelOperations.addAll(createLevelOperations(world, site));
        ACTIVE_TASKS.put(villager.getUuid(), task);
        applyBuilderProfession(villager, task);
        villager.getNavigation().stop();

        VillageExpansionMod.LOGGER.info("Assigned {} house project to villager {} near {}",
                professional ? "professional" : "ordinary", uuidShort(villager.getUuid()), site);
    }

    private static boolean hasActiveBuilder(ServerWorld world, long villageId) {
        RegistryKey<World> dimension = world.getRegistryKey();
        for (BuilderTask task : ACTIVE_TASKS.values()) {
            if (task.villageId == villageId && task.dimension.equals(dimension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve the existing settlement from a nearby villager's HOME memory.
     * A bell or an existing bed is used as a fallback for partially initialized villages.
     */
    private static BlockPos findVillageAnchor(ServerWorld world, VillagerEntity subject) {
        BlockPos best = null;
        double bestDistance = 64.0D * 64.0D;

        for (Entity entity : world.iterateEntities()) {
            if (!(entity instanceof VillagerEntity other) || other == subject || !other.isAlive()) {
                continue;
            }
            Optional<GlobalPos> home = other.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME);
            if (home.isEmpty() || !home.get().dimension().equals(world.getRegistryKey())) {
                continue;
            }

            BlockPos pos = home.get().pos();
            double distance = pos.getSquaredDistance(subject.getBlockPos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }

        if (best != null) {
            return best.toImmutable();
        }

        BlockPos origin = subject.getBlockPos();
        BlockPos nearestMarker = null;
        double nearestMarkerDistance = 32.0D * 32.0D;
        int minY = Math.max(world.getBottomY(), origin.getY() - 4);
        int maxY = Math.min(world.getTopY(), origin.getY() + 4);

        for (int x = origin.getX() - 24; x <= origin.getX() + 24; x++) {
            for (int z = origin.getZ() - 24; z <= origin.getZ() + 24; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (!isLoaded(world, candidate)) {
                        continue;
                    }
                    BlockState marker = world.getBlockState(candidate);
                    if (!marker.isOf(Blocks.BELL) && !marker.isIn(BlockTags.BEDS)) {
                        continue;
                    }
                    double distance = candidate.getSquaredDistance(origin);
                    if (distance < nearestMarkerDistance) {
                        nearestMarkerDistance = distance;
                        nearestMarker = candidate;
                    }
                }
            }
        }
        return nearestMarker;
    }

    private static BlockPos findBuildSite(ServerWorld world, VillageExpansionState.VillageRecord village) {
        BlockPos center = village.center();
        for (int radius = 12; radius <= 36; radius += 6) {
            for (int angleStep = 0; angleStep < 16; angleStep++) {
                double angle = (Math.PI * 2.0D * angleStep) / 16.0D;
                int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
                BlockPos candidate = createViableSite(world, x, z);
                if (candidate == null || village.isNearExistingHouse(candidate, 10.0D)) {
                    continue;
                }
                return candidate;
            }
        }
        return null;
    }

    private static BlockPos createViableSite(ServerWorld world, int centerX, int centerZ) {
        List<Integer> groundHeights = new ArrayList<>(25);
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                BlockPos sample = new BlockPos(x, world.getTopY() - 1, z);
                if (!isLoaded(world, sample)) {
                    return null;
                }
                int groundY = findSurfaceY(world, x, z);
                if (groundY == Integer.MIN_VALUE) {
                    return null;
                }
                BlockPos above = new BlockPos(x, groundY + 1, z);
                if (!world.getFluidState(above).isEmpty()) {
                    return null;
                }
                groundHeights.add(groundY);
                minY = Math.min(minY, groundY);
                maxY = Math.max(maxY, groundY);
            }
        }

        // A small amount of slope is allowed; the worker levels it before building.
        if (maxY - minY > 1) {
            return null;
        }
        groundHeights.sort(Integer::compareTo);
        int baseY = groundHeights.get(groundHeights.size() / 2);

        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                int surfaceY = findSurfaceY(world, x, z);
                for (int y = surfaceY + 1; y <= baseY + 8; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!world.getFluidState(pos).isEmpty()) {
                        return null;
                    }
                    if (state.isAir() || state.isIn(BlockTags.LEAVES) || isPlant(state)) {
                        continue;
                    }
                    if (state.isIn(BlockTags.LOGS)) {
                        if (hasNearbyLeaves(world, pos)) {
                            continue;
                        }
                        return null; // Do not build through logs that may belong to player structures.
                    }
                    if (state.hasBlockEntity() || state.isSolidBlock(world, pos)) {
                        return null;
                    }
                }
            }
        }

        return new BlockPos(centerX, baseY, centerZ);
    }

    /**
     * Starts at the closest existing village dirt path. If the settlement has no
     * dirt path yet, the route begins at the terrain near its registered centre.
     */
    private static List<BlockPos> createVillagePath(ServerWorld world, BlockPos villageCenter, BlockPos site) {
        BlockPos start = findNearestVillagePath(world, villageCenter, site);
        if (start == null) {
            int startY = findSurfaceY(world, villageCenter.getX(), villageCenter.getZ());
            if (startY == Integer.MIN_VALUE) {
                return List.of();
            }
            start = new BlockPos(villageCenter.getX(), startY, villageCenter.getZ());
        }

        int endX = site.getX();
        int endZ = site.getZ() + 3; // One block in front of the south-facing house door.
        int endY = findPathSurfaceY(world, endX, endZ);
        if (endY == Integer.MIN_VALUE) {
            return List.of();
        }
        BlockPos end = new BlockPos(endX, endY, endZ);
        return findPathAStar(world, start, end);
    }

    private static BlockPos findNearestVillagePath(ServerWorld world, BlockPos center, BlockPos site) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = 36;

        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                if (!world.isChunkLoaded(new ChunkPos(x >> 4, z >> 4).toLong())) {
                    continue;
                }
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                for (int y = topY; y >= Math.max(world.getBottomY(), topY - 8); y--) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (!world.getBlockState(candidate).isOf(Blocks.DIRT_PATH)) {
                        continue;
                    }
                    double distance = candidate.getSquaredDistance(site);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate.toImmutable();
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** A* over the terrain surface. Trees are traversable at a high cost and are removed first. */
    private static List<BlockPos> findPathAStar(ServerWorld world, BlockPos start, BlockPos goal) {
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::estimate));
        Map<BlockPos, Double> scores = new HashMap<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        Map<Long, Integer> surfaceCache = new HashMap<>();

        BlockPos immutableStart = start.toImmutable();
        BlockPos immutableGoal = goal.toImmutable();
        scores.put(immutableStart, 0.0D);
        open.add(new PathNode(immutableStart, 0.0D, pathHeuristic(immutableStart, immutableGoal)));

        int[][] neighbors = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1}
        };
        int expanded = 0;
        int maxExpanded = 8_000;
        int minX = Math.min(start.getX(), goal.getX()) - 12;
        int maxX = Math.max(start.getX(), goal.getX()) + 12;
        int minZ = Math.min(start.getZ(), goal.getZ()) - 12;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + 12;

        while (!open.isEmpty() && expanded < maxExpanded) {
            PathNode currentNode = open.poll();
            BlockPos current = currentNode.pos();
            if (currentNode.cost() > scores.getOrDefault(current, Double.MAX_VALUE) + 0.0001D) {
                continue;
            }
            if (!closed.add(current)) {
                continue;
            }
            expanded++;

            if (current.equals(immutableGoal)) {
                return reconstructPath(previous, immutableStart, immutableGoal);
            }

            for (int[] offset : neighbors) {
                int x = current.getX() + offset[0];
                int z = current.getZ() + offset[1];
                if (x < minX || x > maxX || z < minZ || z > maxZ) {
                    continue;
                }
                long columnKey = ((long) x << 32) | (z & 0xffffffffL);
                Integer cachedY = surfaceCache.get(columnKey);
                int y;
                if (cachedY != null) {
                    y = cachedY;
                } else {
                    y = findPathSurfaceY(world, x, z);
                    surfaceCache.put(columnKey, y);
                }
                if (y == Integer.MIN_VALUE || Math.abs(y - current.getY()) > 1) {
                    continue;
                }

                BlockPos next = new BlockPos(x, y, z);
                if (closed.contains(next)) {
                    continue;
                }
                double terrainPenalty = pathCellPenalty(world, next);
                if (terrainPenalty < 0.0D) {
                    continue;
                }
                double moveCost = 1.0D + Math.abs(y - current.getY()) * 1.5D + terrainPenalty;
                double tentative = scores.get(current) + moveCost;

                if (tentative < scores.getOrDefault(next, Double.MAX_VALUE)) {
                    scores.put(next, tentative);
                    previous.put(next, current);
                    open.add(new PathNode(next, tentative, tentative + pathHeuristic(next, immutableGoal)));
                }
            }
        }
        return List.of();
    }

    private static double pathHeuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    private static List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> previous,
                                                   BlockPos start, BlockPos goal) {
        List<BlockPos> reversed = new ArrayList<>();
        BlockPos cursor = goal;
        reversed.add(cursor);
        while (!cursor.equals(start)) {
            cursor = previous.get(cursor);
            if (cursor == null) {
                return List.of();
            }
            reversed.add(cursor);
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /** Returns -1 for blocked cells; trees are allowed but expensive because they will be cleared. */
    private static double pathCellPenalty(ServerWorld world, BlockPos ground) {
        if (!isLoaded(world, ground)) {
            return -1.0D;
        }
        BlockState floor = world.getBlockState(ground);
        if (!floor.isOf(Blocks.DIRT_PATH) && !isTerrain(floor)) {
            return -1.0D;
        }
        if (floor.hasBlockEntity()) {
            return -1.0D;
        }

        double penalty = floor.isOf(Blocks.DIRT_PATH) ? 0.0D : 0.15D;
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos above = ground.up(dy);
            if (!isLoaded(world, above) || !world.getFluidState(above).isEmpty()) {
                return -1.0D;
            }
            BlockState state = world.getBlockState(above);
            if (state.isAir() || state.isIn(BlockTags.LEAVES) || isPlant(state)) {
                continue;
            }
            if (state.isIn(BlockTags.LOGS) && hasNearbyLeaves(world, above)) {
                penalty += 8.0D;
                continue;
            }
            return -1.0D;
        }
        return penalty;
    }

    private static int findPathSurfaceY(ServerWorld world, int x, int z) {
        if (!world.isChunkLoaded(new ChunkPos(x >> 4, z >> 4).toLong())) {
            return Integer.MIN_VALUE;
        }
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (; y >= world.getBottomY(); y--) {
            BlockState state = world.getBlockState(new BlockPos(x, y, z));
            if (state.isOf(Blocks.DIRT_PATH) || isTerrain(state)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static List<BlockPos> findTreesAlongPath(ServerWorld world, List<BlockPos> path, BlockPos site) {
        Set<BlockPos> unique = new HashSet<>();
        for (BlockPos pathBlock : path) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    addTreeBasesAtColumn(world, pathBlock.getX() + dx, pathBlock.getZ() + dz, unique);
                }
            }
        }
        for (int x = site.getX() - 2; x <= site.getX() + 2; x++) {
            for (int z = site.getZ() - 2; z <= site.getZ() + 2; z++) {
                addTreeBasesAtColumn(world, x, z, unique);
            }
        }
        List<BlockPos> result = new ArrayList<>(unique);
        BlockPos sortOrigin = path.isEmpty() ? site : path.get(0);
        result.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(sortOrigin)));
        return result;
    }

    private static List<BlockPos> findTreesAlongRoute(ServerWorld world, BlockPos from, BlockPos to) {
        Set<BlockPos> unique = new HashSet<>();
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        steps = Math.max(1, steps);

        for (int i = 0; i <= steps; i++) {
            double alpha = i / (double) steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * alpha);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * alpha);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    addTreeBasesAtColumn(world, x + dx, z + dz, unique);
                }
            }
        }

        for (int x = to.getX() - 2; x <= to.getX() + 2; x++) {
            for (int z = to.getZ() - 2; z <= to.getZ() + 2; z++) {
                addTreeBasesAtColumn(world, x, z, unique);
            }
        }

        List<BlockPos> result = new ArrayList<>(unique);
        result.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(from)));
        return result;
    }

    private static void addTreeBasesAtColumn(ServerWorld world, int x, int z, Set<BlockPos> output) {
        int groundY = findSurfaceY(world, x, z);
        if (groundY == Integer.MIN_VALUE) {
            return;
        }
        for (int y = groundY + 1; y <= groundY + 14; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!isLoaded(world, pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            if (world.getBlockState(pos.down()).isIn(BlockTags.LOGS)) {
                continue;
            }
            if (hasNearbyLeaves(world, pos)) {
                output.add(pos.toImmutable());
                break;
            }
        }
    }

    private static boolean hasNearbyLeaves(ServerWorld world, BlockPos origin) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 5; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (world.getBlockState(origin.add(dx, dy, dz)).isIn(BlockTags.LEAVES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<LevelOperation> createLevelOperations(ServerWorld world, BlockPos site) {
        List<LevelOperation> cuts = new ArrayList<>();
        List<LevelOperation> fills = new ArrayList<>();

        for (int x = site.getX() - 2; x <= site.getX() + 2; x++) {
            for (int z = site.getZ() - 2; z <= site.getZ() + 2; z++) {
                int surfaceY = findSurfaceY(world, x, z);
                if (surfaceY == Integer.MIN_VALUE) {
                    continue;
                }
                if (surfaceY > site.getY()) {
                    for (int y = surfaceY; y > site.getY(); y--) {
                        cuts.add(new LevelOperation(new BlockPos(x, y, z), false));
                    }
                } else if (surfaceY < site.getY()) {
                    for (int y = surfaceY + 1; y <= site.getY(); y++) {
                        fills.add(new LevelOperation(new BlockPos(x, y, z), true));
                    }
                }
            }
        }

        // Cutting first creates the dirt used to fill lower cells.
        cuts.addAll(fills);
        return cuts;
    }

    private static int findSurfaceY(ServerWorld world, int x, int z) {
        if (!world.isChunkLoaded(new ChunkPos(x >> 4, z >> 4).toLong())) {
            return Integer.MIN_VALUE;
        }
        int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
        for (; y >= world.getBottomY(); y--) {
            BlockState state = world.getBlockState(new BlockPos(x, y, z));
            if (isTerrain(state)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isTerrain(BlockState state) {
        // Keep the first release conservative: the worker knows how to reuse
        // dirt and stone drops as fill, but does not yet normalize sand/gravel.
        return state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isOf(Blocks.GRASS_BLOCK);
    }

    private static boolean isPlant(BlockState state) {
        return state.isIn(BlockTags.FLOWERS)
                || state.isOf(Blocks.SHORT_GRASS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.DEAD_BUSH)
                || state.isOf(Blocks.SNOW)
                || state.isOf(Blocks.VINE);
    }

    private static boolean isLoaded(ServerWorld world, BlockPos pos) {
        return world.isChunkLoaded(new ChunkPos(pos).toLong());
    }

    private static BlockPos findStandingPosition(ServerWorld world, BlockPos workBlock, VillagerEntity villager) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        for (int[] offset : offsets) {
            int x = workBlock.getX() + offset[0];
            int z = workBlock.getZ() + offset[1];
            int groundY = findSurfaceY(world, x, z);
            if (groundY == Integer.MIN_VALUE) {
                continue;
            }
            BlockPos stand = new BlockPos(x, groundY + 1, z);
            if (!world.getBlockState(stand).isAir() || !world.getBlockState(stand.up()).isAir()) {
                continue;
            }
            if (!world.getBlockState(stand.down()).isSolidBlock(world, stand.down())) {
                continue;
            }

            double distance = villager.squaredDistanceTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = stand;
            }
        }
        return best;
    }

    private static void tickTask(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.actionTimer > 0) {
            task.actionTimer--;
        }

        switch (task.stage) {
            case CLEAR_PATH -> tickClearPath(world, villager, task);
            case LEVEL_SITE -> tickLevelSite(world, villager, task);
            case BUILD_PATH -> tickBuildPath(world, villager, task);
            case GATHER_WOOD -> tickGatherWood(world, villager, task);
            case GATHER_STONE -> tickGatherStone(world, villager, task);
            case BUILD -> tickBuild(world, villager, task);
        }
    }

    private static void tickClearPath(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        while (task.treeIndex < task.treeTargets.size()
                && !world.getBlockState(task.treeTargets.get(task.treeIndex)).isIn(BlockTags.LOGS)) {
            task.treeIndex++;
        }

        if (task.treeIndex >= task.treeTargets.size()) {
            villager.getNavigation().stop();
            task.stage = Stage.LEVEL_SITE;
            task.moveTarget = null;
            return;
        }

        BlockPos treeBase = task.treeTargets.get(task.treeIndex);
        BlockPos stand = findStandingPosition(world, treeBase, villager);
        if (stand == null) {
            task.treeIndex++;
            return;
        }
        if (!moveNear(villager, task, stand, NAV_SPEED)) {
            return;
        }
        if (task.actionTimer > 0) {
            return;
        }

        equipForWork(villager, Items.WOODEN_AXE);
        villager.swingHand(Hand.MAIN_HAND);
        cutTreeAndClearLeaves(world, villager, treeBase);
        task.wood = countInventoryItems(villager, ItemTags.LOGS) * 4;
        task.treeIndex++;
        task.moveTarget = null;
        task.actionTimer = 8;
    }

    private static int cutTreeAndClearLeaves(ServerWorld world, VillagerEntity villager, BlockPos base) {
        int logs = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 14; dy++) {
                    BlockPos pos = base.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isIn(BlockTags.LOGS) && hasNearbyLeaves(world, pos)) {
                        if (world.breakBlock(pos, true, villager)) {
                            logs += collectDrops(world, villager, pos, ItemTags.LOGS);
                        }
                    } else if (state.isIn(BlockTags.LEAVES)) {
                        world.breakBlock(pos, false, villager);
                    }
                }
            }
        }
        return logs;
    }

    private static void tickLevelSite(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        BlockPos workStand = new BlockPos(task.site.getX(), task.site.getY() + 1, task.site.getZ());
        if (!moveNear(villager, task, workStand, NAV_SPEED)) {
            return;
        }

        if (!task.plotVegetationCleared) {
            clearPlotVegetation(world, villager, task.site);
            task.plotVegetationCleared = true;
            task.moveTarget = null;
        }

        if (task.levelIndex < task.levelOperations.size()) {
            if (task.actionTimer > 0) {
                return;
            }
            LevelOperation operation = task.levelOperations.get(task.levelIndex++);
            BlockState current = world.getBlockState(operation.pos);
            if (operation.fill) {
                if (current.isAir() || !current.isSolidBlock(world, operation.pos)) {
                    BlockState fillState;
                    if (consumeOneTaggedItem(villager, ItemTags.DIRT)) {
                        fillState = Blocks.DIRT.getDefaultState();
                    } else if (consumeOneTaggedItem(villager, ItemTags.STONE_TOOL_MATERIALS)) {
                        fillState = Blocks.COBBLESTONE.getDefaultState();
                    } else {
                        // Cutting operations run first, so a missing fill block is unusual.
                        task.levelIndex--;
                        task.actionTimer = 20;
                        return;
                    }
                    world.setBlockState(operation.pos, fillState, Block.NOTIFY_ALL);
                }
                task.dirt = countInventoryItems(villager, ItemTags.DIRT);
                task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
            } else if (!current.isAir()) {
                if (current.isIn(BlockTags.BASE_STONE_OVERWORLD)) {
                    world.breakBlock(operation.pos, true, villager);
                    collectDrops(world, villager, operation.pos, ItemTags.STONE_TOOL_MATERIALS);
                } else {
                    world.breakBlock(operation.pos, true, villager);
                    collectDrops(world, villager, operation.pos, ItemTags.DIRT);
                }
                task.dirt = countInventoryItems(villager, ItemTags.DIRT);
                task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
            }
            villager.swingHand(Hand.MAIN_HAND);
            task.actionTimer = 4;
            return;
        }

        villager.getNavigation().stop();
        task.moveTarget = null;
        task.stage = Stage.BUILD_PATH;
    }

    private static void tickBuildPath(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        while (task.pathIndex < task.pathBlocks.size()) {
            BlockPos current = task.pathBlocks.get(task.pathIndex);
            BlockState floor = world.getBlockState(current);
            if (floor.isOf(Blocks.DIRT_PATH)) {
                task.pathIndex++;
                continue;
            }
            if (!isTerrain(floor) || floor.hasBlockEntity()) {
                task.pathIndex++;
                continue;
            }
            break;
        }

        if (task.pathIndex >= task.pathBlocks.size()) {
            villager.getNavigation().stop();
            task.moveTarget = null;
            task.stage = Stage.GATHER_WOOD;
            return;
        }

        BlockPos ground = task.pathBlocks.get(task.pathIndex);
        BlockPos stand = ground.up();
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos above = ground.up(dy);
            BlockState obstacle = world.getBlockState(above);
            if (obstacle.isIn(BlockTags.LEAVES) || isPlant(obstacle)) {
                world.breakBlock(above, false, villager);
                continue;
            }
            if (obstacle.isIn(BlockTags.LOGS) && hasNearbyLeaves(world, above)) {
                BlockPos treeBase = above;
                while (world.getBlockState(treeBase.down()).isIn(BlockTags.LOGS)) {
                    treeBase = treeBase.down();
                }
                BlockPos treeStand = findStandingPosition(world, treeBase, villager);
                if (treeStand == null) {
                    task.pathIndex++;
                    task.moveTarget = null;
                    return;
                }
                if (!moveNear(villager, task, treeStand, NAV_SPEED)) {
                    return;
                }
                if (task.actionTimer > 0) {
                    return;
                }
                cutTreeAndClearLeaves(world, villager, treeBase);
                task.wood = countInventoryItems(villager, ItemTags.LOGS) * 4;
                task.moveTarget = null;
                task.actionTimer = 8;
                return;
            }
            if (!obstacle.isAir()) {
                task.pathIndex++;
                task.moveTarget = null;
                return;
            }
        }

        if (!moveNear(villager, task, stand, NAV_SPEED)) {
            return;
        }
        if (task.actionTimer > 0) {
            return;
        }

        BlockState currentFloor = world.getBlockState(ground);
        if (currentFloor.isOf(Blocks.DIRT_PATH)) {
            task.pathIndex++;
            task.moveTarget = null;
            return;
        }
        if (isTerrain(currentFloor) && !currentFloor.hasBlockEntity()) {
            world.setBlockState(ground, Blocks.DIRT_PATH.getDefaultState(), Block.NOTIFY_ALL);
            villager.swingHand(Hand.MAIN_HAND);
            world.playSound(null, ground, SoundEvents.BLOCK_GRAVEL_PLACE,
                    SoundCategory.BLOCKS, 0.45F, 0.9F);
        }
        task.pathIndex++;
        task.moveTarget = null;
        task.actionTimer = 2;
    }

    private static void clearPlotVegetation(ServerWorld world, VillagerEntity villager, BlockPos site) {
        for (int x = site.getX() - 2; x <= site.getX() + 2; x++) {
            for (int z = site.getZ() - 2; z <= site.getZ() + 2; z++) {
                for (int y = site.getY() + 1; y <= site.getY() + 8; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isIn(BlockTags.LEAVES)
                            || (state.isIn(BlockTags.LOGS) && hasNearbyLeaves(world, pos))
                            || isPlant(state)) {
                        world.breakBlock(pos, false, villager);
                    }
                }
            }
        }
    }

    private static void tickGatherWood(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.wood >= WOOD_REQUIRED) {
            villager.getNavigation().stop();
            task.moveTarget = null;
            task.stage = Stage.GATHER_STONE;
            return;
        }

        if (task.currentResource == null) {
            if (world.getTime() < task.nextResourceSearch) {
                return;
            }
            ResourceTarget target = findWoodTarget(world, task.site, villager);
            if (target == null) {
                task.nextResourceSearch = world.getTime() + 200L;
                return;
            }
            task.currentResource = target;
            task.moveTarget = null;
        }

        ResourceTarget target = task.currentResource;
        if (!world.getBlockState(target.block).isIn(BlockTags.LOGS)) {
            task.currentResource = null;
            task.moveTarget = null;
            return;
        }
        if (!moveNear(villager, task, target.stand, NAV_SPEED)) {
            return;
        }
        if (task.actionTimer > 0) {
            return;
        }

        equipForWork(villager, Items.WOODEN_AXE);
        villager.swingHand(Hand.MAIN_HAND);
        int logs = cutTreeAndClearLeaves(world, villager, target.block);
        task.wood = countInventoryItems(villager, ItemTags.LOGS) * 4;
        task.currentResource = null;
        task.moveTarget = null;
        task.actionTimer = 10;
    }

    private static ResourceTarget findWoodTarget(ServerWorld world, BlockPos around, VillagerEntity villager) {
        ResourceTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = 32;

        for (int x = around.getX() - radius; x <= around.getX() + radius; x += 1) {
            for (int z = around.getZ() - radius; z <= around.getZ() + radius; z += 1) {
                int groundY = findSurfaceY(world, x, z);
                if (groundY == Integer.MIN_VALUE) {
                    continue;
                }
                for (int y = groundY + 1; y <= groundY + 14; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isLoaded(world, pos) || !world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                        continue;
                    }
                    if (world.getBlockState(pos.down()).isIn(BlockTags.LOGS) || !hasNearbyLeaves(world, pos)) {
                        continue;
                    }
                    BlockPos stand = findStandingPosition(world, pos, villager);
                    if (stand == null) {
                        continue;
                    }
                    double distance = pos.getSquaredDistance(villager.getBlockPos());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new ResourceTarget(pos.toImmutable(), stand);
                    }
                }
            }
        }
        return best;
    }

    private static void tickGatherStone(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.stone >= STONE_REQUIRED) {
            villager.getNavigation().stop();
            task.moveTarget = null;
            task.blueprint = createBlueprint(task.site, task.professional);
            task.stage = Stage.BUILD;
            return;
        }

        if (task.currentMine == null) {
            if (world.getTime() < task.nextResourceSearch) {
                return;
            }
            MiningTarget target = findStoneTarget(world, task.site, villager);
            if (target == null) {
                task.nextResourceSearch = world.getTime() + 200L;
                return;
            }
            task.currentMine = target;
            task.coverIndex = 0;
            task.moveTarget = null;
        }

        MiningTarget mine = task.currentMine;
        if (!moveNear(villager, task, mine.stand, NAV_SPEED)) {
            return;
        }
        if (task.actionTimer > 0) {
            return;
        }

        equipForWork(villager, Items.WOODEN_PICKAXE);
        if (task.coverIndex < mine.cover.size()) {
            CoverBlock cover = mine.cover.get(task.coverIndex++);
            BlockState current = world.getBlockState(cover.pos);
            if (!current.isAir()) {
                world.breakBlock(cover.pos, false, villager);
            }
            villager.swingHand(Hand.MAIN_HAND);
            task.actionTimer = 6;
            return;
        }

        BlockState rock = world.getBlockState(mine.rock);
        if (rock.isIn(BlockTags.BASE_STONE_OVERWORLD)) {
            world.breakBlock(mine.rock, true, villager);
            collectDrops(world, villager, mine.rock, ItemTags.STONE_TOOL_MATERIALS);
            task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
            // The worker backfills the narrow borehole with dirt instead of
            // leaving a dangerous open shaft beside the settlement.
            world.setBlockState(mine.rock, Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
            for (int i = mine.cover.size() - 1; i >= 0; i--) {
                CoverBlock cover = mine.cover.get(i);
                world.setBlockState(cover.pos, cover.originalState, Block.NOTIFY_ALL);
            }
        } else {
            task.currentMine = null;
            task.moveTarget = null;
            return;
        }

        villager.swingHand(Hand.MAIN_HAND);
        task.currentMine = null;
        task.coverIndex = 0;
        task.moveTarget = null;
        task.actionTimer = 10;
    }

    private static MiningTarget findStoneTarget(ServerWorld world, BlockPos around, VillagerEntity villager) {
        MiningTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = 22;

        for (int x = around.getX() - radius; x <= around.getX() + radius; x++) {
            for (int z = around.getZ() - radius; z <= around.getZ() + radius; z++) {
                int surfaceY = findSurfaceY(world, x, z);
                if (surfaceY == Integer.MIN_VALUE) {
                    continue;
                }
                BlockPos surfacePos = new BlockPos(x, surfaceY, z);
                if (!isLoaded(world, surfacePos) || !isTerrain(world.getBlockState(surfacePos))) {
                    continue;
                }

                for (int y = surfaceY; y >= Math.max(world.getBottomY(), surfaceY - 12); y--) {
                    BlockPos rockPos = new BlockPos(x, y, z);
                    BlockState rockState = world.getBlockState(rockPos);
                    if (!rockState.isIn(BlockTags.BASE_STONE_OVERWORLD)) {
                        continue;
                    }

                    List<CoverBlock> cover = new ArrayList<>();
                    boolean safeColumn = true;
                    for (int coverY = surfaceY; coverY > y; coverY--) {
                        BlockPos coverPos = new BlockPos(x, coverY, z);
                        BlockState coverState = world.getBlockState(coverPos);
                        if (!(coverState.isIn(BlockTags.DIRT)
                                || coverState.isIn(BlockTags.BASE_STONE_OVERWORLD)
                                || coverState.isOf(Blocks.GRAVEL)
                                || coverState.isOf(Blocks.SAND))) {
                            safeColumn = false;
                            break;
                        }
                        cover.add(new CoverBlock(coverPos.toImmutable(), coverState));
                    }
                    if (!safeColumn) {
                        continue;
                    }

                    BlockPos stand = findStandingPosition(world, surfacePos, villager);
                    if (stand == null) {
                        continue;
                    }
                    double distance = surfacePos.getSquaredDistance(villager.getBlockPos());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new MiningTarget(rockPos.toImmutable(), stand, cover);
                    }
                    // The shallowest stone layer in this column is enough.
                    break;
                }
            }
        }
        return best;
    }

    /** Collect matching block drops into the villager's real vanilla inventory. */
    private static int collectDrops(ServerWorld world, VillagerEntity villager, BlockPos origin, TagKey<Item> acceptedTag) {
        int collected = 0;
        Box area = new Box(
                origin.getX() - 3.0D, origin.getY() - 3.0D, origin.getZ() - 3.0D,
                origin.getX() + 4.0D, origin.getY() + 4.0D, origin.getZ() + 4.0D
        );
        for (ItemEntity itemEntity : world.getEntitiesByClass(ItemEntity.class, area, item -> !item.isRemoved())) {
            ItemStack dropped = itemEntity.getStack();
            if (dropped.isEmpty() || !dropped.isIn(acceptedTag)) {
                continue;
            }

            int before = dropped.getCount();
            ItemStack remainder = villager.getInventory().addStack(dropped.copy());
            int accepted = before - remainder.getCount();
            if (accepted > 0) {
                collected += accepted;
                if (remainder.isEmpty()) {
                    itemEntity.discard();
                } else {
                    itemEntity.setStack(remainder);
                }
            }
        }
        return collected;
    }

    private static int countInventoryItems(VillagerEntity villager, TagKey<Item> itemTag) {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            ItemStack stack = villager.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isIn(itemTag)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean consumeOneTaggedItem(VillagerEntity villager, TagKey<Item> itemTag) {
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            ItemStack stack = villager.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isIn(itemTag)) {
                stack.decrement(1);
                villager.getInventory().markDirty();
                return true;
            }
        }
        return false;
    }

    private static void tickBuild(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        BlockPos workStand = new BlockPos(task.site.getX(), task.site.getY() + 1, task.site.getZ());
        if (!moveNear(villager, task, workStand, NAV_SPEED)) {
            return;
        }

        if (task.buildIndex >= task.blueprint.size()) {
            completeProject(world, villager, task);
            return;
        }
        if (task.actionTimer > 0) {
            return;
        }

        equipForWork(villager, Items.OAK_PLANKS);
        BlockPlacement placement = task.blueprint.get(task.buildIndex++);
        if (!world.getBlockState(placement.pos).isAir()) {
            // Never overwrite a block placed after the plot was reserved.
            task.actionTimer = 1;
            return;
        }
        if (placement.state.isOf(Blocks.COBBLESTONE)) {
            if (!consumeOneTaggedItem(villager, ItemTags.STONE_TOOL_MATERIALS)) {
                task.stage = Stage.GATHER_STONE;
                task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
                task.buildIndex--;
                task.moveTarget = null;
                return;
            }
        } else if (placement.state.isIn(BlockTags.LOGS)) {
            if (!consumeOneTaggedItem(villager, ItemTags.LOGS)) {
                task.stage = Stage.GATHER_WOOD;
                task.wood = countInventoryItems(villager, ItemTags.LOGS) * 4;
                task.buildIndex--;
                task.moveTarget = null;
                return;
            }
        } else if (placement.state.isIn(BlockTags.PLANKS) || placement.state.isOf(Blocks.OAK_DOOR)) {
            if (task.woodPiecesPlaced % 4 == 0 && !consumeOneTaggedItem(villager, ItemTags.LOGS)) {
                task.stage = Stage.GATHER_WOOD;
                task.wood = countInventoryItems(villager, ItemTags.LOGS) * 4;
                task.buildIndex--;
                task.moveTarget = null;
                return;
            }
            task.woodPiecesPlaced++;
        }
        world.setBlockState(placement.pos, placement.state, Block.NOTIFY_ALL);
        villager.swingHand(Hand.MAIN_HAND);
        task.actionTimer = 2;
    }

    private static List<BlockPlacement> createBlueprint(BlockPos base, boolean professional) {
        List<BlockPlacement> blocks = new ArrayList<>();
        // Raised foundation and wooden floor.
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                add(blocks, base, x, 1, z, edge ? Blocks.COBBLESTONE.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState());
            }
        }

        // Three-block walls with windows.
        for (int y = 2; y <= 4; y++) {
            for (int x = 0; x < 5; x++) {
                for (int z : new int[]{0, 4}) {
                    if (z == 4 && x == 2 && y <= 3) {
                        continue;
                    }
                    boolean corner = (x == 0 || x == 4);
                    boolean window = y == 3 && ((z == 0 && x == 2) || (z == 4 && x == 1));
                    BlockState wall = window ? Blocks.GLASS.getDefaultState()
                            : Blocks.SPRUCE_PLANKS.getDefaultState();
                    add(blocks, base, x, y, z, wall);
                }
            }
            for (int z = 1; z <= 3; z++) {
                for (int x : new int[]{0, 4}) {
                    boolean window = y == 3 && z == 2;
                    BlockState wall = window ? Blocks.GLASS.getDefaultState()
                            : Blocks.SPRUCE_PLANKS.getDefaultState();
                    add(blocks, base, x, y, z, wall);
                }
            }
        }

        // Roof silhouette: broad eaves, then two smaller layers.
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                add(blocks, base, x, 5, z, Blocks.SPRUCE_PLANKS.getDefaultState());
            }
        }
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                add(blocks, base, x, 6, z, Blocks.SPRUCE_PLANKS.getDefaultState());
            }
        }
        add(blocks, base, 2, 7, 2, Blocks.SPRUCE_PLANKS.getDefaultState());

        // Door: lower and upper halves are separate block states.
        BlockState door = Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH);
        add(blocks, base, 2, 2, 4, door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        add(blocks, base, 2, 3, 4, door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        // A bed in every home. Foot at x=1; head at x=2, facing east.
        BlockState bed = Blocks.WHITE_BED.getDefaultState().with(BedBlock.FACING, Direction.EAST);
        add(blocks, base, 1, 2, 1, bed.with(BedBlock.PART, BedPart.FOOT));
        add(blocks, base, 2, 2, 1, bed.with(BedBlock.PART, BedPart.HEAD));

        if (professional) {
            add(blocks, base, 3, 2, 2, chooseWorkstation(base));
        }

        // Basic interior lighting helps keep newly built homes usable at night.
        add(blocks, base, 1, 2, 3, Blocks.TORCH.getDefaultState());
        add(blocks, base, 3, 2, 3, Blocks.TORCH.getDefaultState());

        return blocks;
    }

    private static BlockState chooseWorkstation(BlockPos base) {
        // Rotate through useful vanilla professions based on the site's coordinates.
        BlockState[] workstations = {
                Blocks.COMPOSTER.getDefaultState(),
                Blocks.BLAST_FURNACE.getDefaultState(),
                Blocks.SMITHING_TABLE.getDefaultState(),
                Blocks.SMOKER.getDefaultState(),
                Blocks.BARREL.getDefaultState(),
                Blocks.CARTOGRAPHY_TABLE.getDefaultState(),
                Blocks.FLETCHING_TABLE.getDefaultState(),
                Blocks.LECTERN.getDefaultState(),
                Blocks.STONECUTTER.getDefaultState(),
                Blocks.CAULDRON.getDefaultState(),
                Blocks.BREWING_STAND.getDefaultState(),
                Blocks.LOOM.getDefaultState()
        };
        int index = Math.floorMod(base.getX() * 31 + base.getZ(), workstations.length);
        return workstations[index];
    }

    private static void add(List<BlockPlacement> blocks, BlockPos base, int x, int y, int z, BlockState state) {
        blocks.add(new BlockPlacement(base.add(x - 2, y, z - 2), state));
    }

    private static boolean moveNear(VillagerEntity villager, BuilderTask task, BlockPos target, double speed) {
        double distanceSquared = villager.squaredDistanceTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
        if (distanceSquared <= 4.0D) {
            task.moveTarget = target.toImmutable();
            return true;
        }

        if (task.moveTarget == null || !task.moveTarget.equals(target) || villager.getNavigation().isIdle()) {
            villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, speed);
            task.moveTarget = target.toImmutable();
        }
        return false;
    }

    private static void equipForWork(VillagerEntity villager, net.minecraft.item.Item item) {
        ItemStack current = villager.getEquippedStack(EquipmentSlot.MAINHAND);
        if (!current.isOf(item)) {
            villager.equipStack(EquipmentSlot.MAINHAND, new ItemStack(item));
        }
    }

    private static void completeProject(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE,
                VillageExpansionState.STORAGE_ID
        );
        VillageExpansionState.VillageRecord village = saved.getVillage(task.villageId);
        if (village != null) {
            village.registerHouse(task.site, task.professional);
            saved.markDirty();
        }

        // Reserve the bed memory for the builder so it does not immediately
        // start another house while vanilla POI sensors update.
        BlockPos bedPos = task.site.add(0, 2, -1);
        villager.getBrain().remember(MemoryModuleType.HOME, GlobalPos.create(world.getRegistryKey(), bedPos));
        villager.getNavigation().stop();
        restoreBuilderProfession(villager, task);
        villager.equipStack(EquipmentSlot.MAINHAND, task.originalMainHand.copy());
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                task.site.getX() + 0.5D, task.site.getY() + 4.0D, task.site.getZ() + 0.5D,
                8, 1.2D, 0.8D, 1.2D, 0.05D);
        world.playSound(null, task.site, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.9F, 1.0F);

        ACTIVE_TASKS.remove(villager.getUuid());
        RETRY_AT.put(villager.getUuid(), world.getTime() + 24_000L);
        VillageExpansionMod.LOGGER.info("Villager {} completed a {} house at {}. Village totals: {} ordinary, {} professional.",
                uuidShort(villager.getUuid()), task.professional ? "professional" : "ordinary", task.site,
                village == null ? "?" : village.commonHouses(),
                village == null ? "?" : village.professionalHouses());
    }

    private static void abandonTask(VillagerEntity villager, BuilderTask task) {
        villager.getNavigation().stop();
        restoreBuilderProfession(villager, task);
        villager.equipStack(EquipmentSlot.MAINHAND, task.originalMainHand.copy());
        ACTIVE_TASKS.remove(villager.getUuid());
        RETRY_AT.put(villager.getUuid(), 0L);
    }

    private static void applyBuilderProfession(VillagerEntity villager, BuilderTask task) {
        Identifier originalId = Registries.VILLAGER_PROFESSION.getId(task.originalVillagerData.getProfession());
        villager.addCommandTag(ORIGINAL_PROFESSION_TAG_PREFIX + originalId);
        villager.setVillagerData(task.originalVillagerData.withProfession(BuilderProfession.BUILDER));
    }

    private static void restoreBuilderProfession(VillagerEntity villager, BuilderTask task) {
        villager.setVillagerData(task.originalVillagerData);
        removeOriginalProfessionTags(villager);
    }

    /** Recovers the original job if the server restarted while a villager was building. */
    private static void restoreOrphanedBuilderProfession(VillagerEntity villager) {
        if (!villager.getVillagerData().getProfession().equals(BuilderProfession.BUILDER)) {
            return;
        }
        String savedTag = villager.getCommandTags().stream()
                .filter(tag -> tag.startsWith(ORIGINAL_PROFESSION_TAG_PREFIX))
                .findFirst().orElse(null);
        if (savedTag == null) {
            villager.setVillagerData(villager.getVillagerData().withProfession(VillagerProfession.NONE));
            return;
        }
        Identifier originalId = Identifier.tryParse(savedTag.substring(ORIGINAL_PROFESSION_TAG_PREFIX.length()));
        VillagerProfession original = originalId == null
                ? VillagerProfession.NONE
                : Registries.VILLAGER_PROFESSION.getOrEmpty(originalId).orElse(VillagerProfession.NONE);
        villager.setVillagerData(villager.getVillagerData().withProfession(original));
        removeOriginalProfessionTags(villager);
    }

    private static void removeOriginalProfessionTags(VillagerEntity villager) {
        for (String tag : new ArrayList<>(villager.getCommandTags())) {
            if (tag.startsWith(ORIGINAL_PROFESSION_TAG_PREFIX)) {
                villager.removeCommandTag(tag);
            }
        }
    }

    private static String uuidShort(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private enum Stage {
        CLEAR_PATH,
        LEVEL_SITE,
        BUILD_PATH,
        GATHER_WOOD,
        GATHER_STONE,
        BUILD
    }

    private static final class BuilderTask {
        private final RegistryKey<World> dimension;
        private final long villageId;
        private final BlockPos site;
        private final boolean professional;
        private final ItemStack originalMainHand;
        private final VillagerData originalVillagerData;
        private final List<BlockPos> treeTargets = new ArrayList<>();
        private final List<BlockPos> pathBlocks = new ArrayList<>();
        private final List<LevelOperation> levelOperations = new ArrayList<>();
        private List<BlockPlacement> blueprint = new ArrayList<>();

        private Stage stage = Stage.CLEAR_PATH;
        private int treeIndex;
        private int levelIndex;
        private int pathIndex;
        private int buildIndex;
        private int coverIndex;
        private int wood;
        private int stone;
        private int dirt;
        private int woodPiecesPlaced;
        private int actionTimer;
        private long nextResourceSearch;
        private BlockPos moveTarget;
        private ResourceTarget currentResource;
        private MiningTarget currentMine;
        private boolean plotVegetationCleared;

        private BuilderTask(RegistryKey<World> dimension, long villageId, BlockPos site,
                            boolean professional, ItemStack originalMainHand, VillagerData originalVillagerData) {
            this.dimension = dimension;
            this.villageId = villageId;
            this.site = site.toImmutable();
            this.professional = professional;
            this.originalMainHand = originalMainHand;
            this.originalVillagerData = originalVillagerData;
        }
    }

    private record LevelOperation(BlockPos pos, boolean fill) {
    }

    private record BlockPlacement(BlockPos pos, BlockState state) {
    }

    private record ResourceTarget(BlockPos block, BlockPos stand) {
    }

    private record CoverBlock(BlockPos pos, BlockState originalState) {
    }

    private record MiningTarget(BlockPos rock, BlockPos stand, List<CoverBlock> cover) {
    }

    private record PathNode(BlockPos pos, double cost, double estimate) {
    }
}
