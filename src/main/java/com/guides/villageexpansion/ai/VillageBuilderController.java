package com.guides.villageexpansion.ai;

import com.guides.villageexpansion.VillageExpansionMod;
import com.guides.villageexpansion.BuilderProfession;
import com.guides.villageexpansion.data.VillageExpansionState;
import com.guides.villageexpansion.config.VillageExpansionConfig;
import com.guides.villageexpansion.structures.VillageTemplateLibrary;
import com.guides.villageexpansion.structures.VillageTemplateLibrary.HouseTemplate;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.SmeltingRecipe;
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
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
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
    private static final String TASK_TAG = "village_expansion_task";
    private static final String ORIGINAL_PROFESSION_TAG_PREFIX = "village_expansion_original_profession=";

    private static final Map<UUID, BuilderTask> ACTIVE_TASKS = new HashMap<>();
    private static final int[][] SAPLING_SPACING_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[][] STANDING_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[] CAVE_ROOF_PROBES = {2, 4, 7, 10};
    /** Stable round-robin index; avoids allocating/sorting active task lists every tick. */
    private static final List<UUID> ACTIVE_TASK_ORDER = new ArrayList<>();
    private static final Map<UUID, Long> RETRY_AT = new HashMap<>();
    private static final Map<RegistryKey<World>, WorldRuntime> WORLD_RUNTIMES = new HashMap<>();
    private static RegistryKey<World> lastLazyPlanningDimension;
    private static UUID lastLazyPlanningOwner;
    private static int lazyPlanningCursor;
    private static long lastLazyPlanningTick = Long.MIN_VALUE;

    private VillageBuilderController() {
    }

    public static void tickWorld(ServerWorld world) {
        if (!VillageExpansionConfig.enabled) return;
        long now = world.getTime();
        if (now % VillageExpansionConfig.taskTickInterval != 0L) return;
        long controllerTickStarted = System.nanoTime();
        WorldRuntime runtime = WORLD_RUNTIMES.computeIfAbsent(world.getRegistryKey(), ignored -> new WorldRuntime());

        // Loaded entities are maintained by Fabric lifecycle events. No full-world
        // entity iteration is performed by the builder controller; saved task
        // restoration is limited to one villager per tick.
        if (runtime.savedState == null) {
            runtime.savedState = world.getPersistentStateManager().getOrCreate(
                    VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        }
        processOnePendingVillagerLoad(world, runtime);
        VillageExpansionState saved = runtime.savedState;
        // A slow save restoration or state initialization uses this tick's budget;
        // defer the next expensive action instead of stacking work in one tick.
        boolean allowBuilderSlice = System.nanoTime() - controllerTickStarted < 3_000_000L;

        // Assignment can perform terrain/template/path searches. Attempt at most
        // one unemployed villager per interval, including failed attempts; a
        // failed candidate must not trigger a burst of expensive retries for all
        // remaining villagers in the same tick.
        if (now >= runtime.nextAssignmentAt && !runtime.loadedVillagerIds.isEmpty()) {
            long assignmentStarted = System.nanoTime();
            attemptOneBuilderAssignment(world, runtime, now);
            if (System.nanoTime() - assignmentStarted >= 3_000_000L) allowBuilderSlice = false;
        }

        // Round-robin the expensive custom task state machine. Vanilla villager
        // Brain, movement, look and navigation continue ticking every normal game
        // tick; this controller only advances construction logic in small slices.
        if (allowBuilderSlice && !ACTIVE_TASK_ORDER.isEmpty()
                && now % VillageExpansionConfig.builderTaskGlobalIntervalTicks == 0L) {
            int budget = VillageExpansionConfig.builderTaskUpdatesPerTick;
            int examined = 0;
            int total = ACTIVE_TASK_ORDER.size();
            while (budget > 0 && examined < total && !ACTIVE_TASK_ORDER.isEmpty()) {
                if (runtime.taskCursor >= ACTIVE_TASK_ORDER.size()) runtime.taskCursor = 0;
                UUID uuid = ACTIVE_TASK_ORDER.get(runtime.taskCursor++);
                examined++;
                BuilderTask task = ACTIVE_TASKS.get(uuid);
                if (task == null || !task.dimension.equals(world.getRegistryKey())
                        || now < task.nextControllerUpdateAt) continue;
                Entity entity = world.getEntity(uuid);
                if (!(entity instanceof VillagerEntity villager)) continue; // unloaded chunk: retain the task
                if (!villager.isAlive() || villager.isRemoved()) {
                    abandonTask(world, villager, task);
                    continue;
                }

                long taskTickStarted = System.nanoTime();
                tickTask(world, villager, task);
                task.nextControllerUpdateAt = now + Math.max(40, VillageExpansionConfig.builderTaskMinIntervalTicks);
                budget--;
                long taskTickMillis = (System.nanoTime() - taskTickStarted) / 1_000_000L;
                if (taskTickMillis >= 10L) {
                    VillageExpansionMod.LOGGER.warn("Builder task slice for {} took {} ms (stage {}, warning threshold 10 ms)",
                            uuidShort(uuid), taskTickMillis, task.stage);
                }
                // Task snapshots are less frequent; initial assignment is saved
                // immediately and active work remains recoverable after reload.
                if (ACTIVE_TASKS.get(uuid) == task && now >= task.nextTaskSaveAt) {
                    saved.saveActiveTask(uuid, writeTask(task));
                    task.nextTaskSaveAt = now + 100L;
                }
                total = ACTIVE_TASK_ORDER.size();
            }
        }

        // Planning/founding are lower-priority than live construction and only run
        // when the controller still has time left in its small per-tick budget.
        // If a task slice is expensive, these operations simply resume next tick.
        if (System.nanoTime() - controllerTickStarted < 2_000_000L) {
            advanceOneLazyPlanningStep(world);
        }
        if (saved != null && now >= runtime.nextFoundingCheckAt
                && System.nanoTime() - controllerTickStarted < 2_000_000L) {
            updateFoundingCompletion(world, saved);
            runtime.nextFoundingCheckAt = now + 200L;
        }
        long controllerTickMillis = (System.nanoTime() - controllerTickStarted) / 1_000_000L;
        if (controllerTickMillis >= 10L) {
            VillageExpansionMod.LOGGER.warn("Village controller slice took {} ms ({} cached villagers, {} active tasks; warning threshold 10 ms)",
                    controllerTickMillis, runtime.loadedVillagerIds.size(), ACTIVE_TASKS.size());
        }
    }

    /** Snapshot only the villager UUID cache; never iterate every entity in the world. */
    public static List<VillagerEntity> getLoadedVillagers(ServerWorld world) {
        WorldRuntime runtime = WORLD_RUNTIMES.get(world.getRegistryKey());
        if (runtime == null || runtime.loadedVillagerIds.isEmpty()) return List.of();
        List<VillagerEntity> result = new ArrayList<>(runtime.loadedVillagerIds.size());
        for (UUID uuid : runtime.loadedVillagerIds) {
            Entity entity = world.getEntity(uuid);
            if (entity instanceof VillagerEntity villager && villager.isAlive() && !villager.isRemoved()) {
                result.add(villager);
            }
        }
        return result;
    }

    /** Called by Fabric when a villager enters a ServerWorld, including spawn eggs. */
    public static void onVillagerEntityLoad(Entity entity, ServerWorld world) {
        if (!(entity instanceof VillagerEntity villager)) return;
        WorldRuntime runtime = WORLD_RUNTIMES.computeIfAbsent(world.getRegistryKey(), ignored -> new WorldRuntime());
        if (runtime.savedState == null) {
            runtime.savedState = world.getPersistentStateManager().getOrCreate(
                    VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        }
        queueLoadedVillager(runtime, villager);
    }

    /** Chunk unloads remove only the live cache entry; active task state remains resumable. */
    public static void onVillagerEntityUnload(Entity entity, ServerWorld world) {
        if (!(entity instanceof VillagerEntity villager)) return;
        UUID uuid = villager.getUuid();
        WorldRuntime runtime = WORLD_RUNTIMES.get(world.getRegistryKey());
        if (runtime != null) {
            if (runtime.loadedVillagerIdSet.remove(uuid)) runtime.loadedVillagerIds.remove(uuid);
            runtime.pendingVillagerLoadSet.remove(uuid);
        }
        BuilderTask task = ACTIVE_TASKS.get(uuid);
        if (task == null) return;
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        if (!villager.isAlive()) {
            saved.removeActiveTask(uuid);
            unregisterActiveTask(uuid);
        } else {
            saved.saveActiveTask(uuid, writeTask(task));
        }
    }

    private static void queueLoadedVillager(WorldRuntime runtime, VillagerEntity villager) {
        UUID uuid = villager.getUuid();
        if (runtime.loadedVillagerIdSet.add(uuid)) runtime.loadedVillagerIds.add(uuid);
        if (runtime.pendingVillagerLoadSet.add(uuid)) runtime.pendingVillagerLoads.addLast(uuid);
    }


    /** Restore at most one saved builder per tick so loading a busy chunk cannot spike MSPT. */
    private static void processOnePendingVillagerLoad(ServerWorld world, WorldRuntime runtime) {
        UUID uuid = runtime.pendingVillagerLoads.pollFirst();
        if (uuid == null) return;
        runtime.pendingVillagerLoadSet.remove(uuid);
        Entity entity = world.getEntity(uuid);
        if (!(entity instanceof VillagerEntity villager) || !villager.isAlive()) return;
        if (ACTIVE_TASKS.containsKey(uuid)) return;
        NbtCompound taskData = runtime.savedState.getActiveTask(uuid);
        if (taskData != null) {
            BuilderTask restored = restoreTask(world, villager, taskData);
            if (restored != null) {
                registerActiveTask(uuid, restored);
                removeOriginalProfessionTags(villager);
                applyBuilderProfession(villager, restored);
            } else {
                runtime.savedState.removeActiveTask(uuid);
            }
        } else {
            restoreOrphanedBuilderProfession(villager);
        }
    }

    private static void attemptOneBuilderAssignment(ServerWorld world, WorldRuntime runtime, long now) {
        int size = runtime.loadedVillagerIds.size();
        if (size == 0) {
            runtime.nextAssignmentAt = now + VillageExpansionConfig.assignmentScanInterval;
            return;
        }
        for (int checked = 0; checked < size; checked++) {
            if (runtime.assignmentCursor >= size) runtime.assignmentCursor = 0;
            UUID uuid = runtime.loadedVillagerIds.get(runtime.assignmentCursor++);
            if (ACTIVE_TASKS.containsKey(uuid) || runtime.pendingVillagerLoadSet.contains(uuid)
                    || now < RETRY_AT.getOrDefault(uuid, 0L)) continue;
            Entity entity = world.getEntity(uuid);
            if (!(entity instanceof VillagerEntity villager) || !villager.isAlive() || villager.isBaby()
                    || villager.isAiDisabled() || villager.hasCustomer()
                    || !villager.getVillagerData().getProfession().equals(VillagerProfession.NONE)) continue;
            long started = System.nanoTime();
            tryAssignBuilder(world, villager);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            if (elapsedMillis >= 10L) {
                VillageExpansionMod.LOGGER.warn("Builder assignment attempt for {} took {} ms (warning threshold 10 ms)",
                        uuidShort(uuid), elapsedMillis);
            }
            // Exactly one expensive attempt, successful or not, per assignment window.
            runtime.nextAssignmentAt = now + VillageExpansionConfig.assignmentScanInterval;
            return;
        }
        runtime.nextAssignmentAt = now + VillageExpansionConfig.assignmentScanInterval;
    }

    private static void registerActiveTask(UUID uuid, BuilderTask task) {
        if (!ACTIVE_TASKS.containsKey(uuid)) ACTIVE_TASK_ORDER.add(uuid);
        ACTIVE_TASKS.put(uuid, task);
    }

    private static void unregisterActiveTask(UUID uuid) {
        ACTIVE_TASKS.remove(uuid);
        ACTIVE_TASK_ORDER.remove(uuid);
        for (WorldRuntime runtime : WORLD_RUNTIMES.values()) {
            if (runtime.taskCursor > ACTIVE_TASK_ORDER.size()) runtime.taskCursor = 0;
        }
    }

    private static final class WorldRuntime {
        private final List<UUID> loadedVillagerIds = new ArrayList<>();
        private final Set<UUID> loadedVillagerIdSet = new HashSet<>();
        private final ArrayDeque<UUID> pendingVillagerLoads = new ArrayDeque<>();
        private final Set<UUID> pendingVillagerLoadSet = new HashSet<>();
        private VillageExpansionState savedState;
        private long nextAssignmentAt = Long.MIN_VALUE;
        private long nextFoundingCheckAt = Long.MIN_VALUE;
        private int assignmentCursor;
        private int taskCursor;
        private int siteSearchCursor;
        private long nextSpatialSearchAt = Long.MIN_VALUE;
    }

    /**
     * Global-per-dimension throttle for world scans. Resource scanners are still
     * bounded/sampled, but this permit also prevents 20 builders from launching
     * their scans on the same server tick.
     */
    private static boolean acquireSpatialSearchPermit(ServerWorld world) {
        WorldRuntime runtime = WORLD_RUNTIMES.computeIfAbsent(world.getRegistryKey(), ignored -> new WorldRuntime());
        long now = world.getTime();
        if (now < runtime.nextSpatialSearchAt) return false;
        runtime.nextSpatialSearchAt = now + VillageExpansionConfig.spatialSearchIntervalTicks;
        return true;
    }

    /**
     * Called immediately after the vanilla Brain tick. The Brain is never disabled;
     * construction navigation simply gets the final movement command while work is active.
     */
    public static void afterVanillaBrainTick(VillagerEntity villager) {
        BuilderTask task = ACTIVE_TASKS.get(villager.getUuid());
        if (task == null || !(villager.getWorld() instanceof ServerWorld serverWorld)) return;
        long now = serverWorld.getTime();
        if (now < task.nextBrainNavigationCheckAt) return;
        task.nextBrainNavigationCheckAt = now + 20L;
        if (task.cachedNearbyDanger || (villager.getAttacker() != null && villager.getAttacker().isAlive())
                || healthEmergency(villager) || now < task.pausedUntil
                || task.sleepingAtNight || nightWithAssignedHome(villager)) return;

        if (task.moveTarget == null) {
            // The controller has no active destination this tick (for example,
            // while waiting for a resource-search cooldown or finishing a local
            // action). Do not cancel the vanilla Brain's idle/look/navigation
            // behaviors when there is no construction movement command to apply.
            return;
        }
        double distance = villager.squaredDistanceTo(task.moveTarget.getX() + 0.5D,
                task.moveTarget.getY(), task.moveTarget.getZ() + 0.5D);
        if (distance <= 4.0D) {
            villager.getNavigation().stop();
            return;
        }
        BlockPos navigationTarget = villager.getNavigation().getTargetPos();
        boolean navigationWasOverridden = navigationTarget == null
                || navigationTarget.getSquaredDistance(task.moveTarget) > 4.0D;
        if (villager.getNavigation().isIdle() || navigationWasOverridden
                || now - task.lastBrainNavigationOverride >= VillageExpansionConfig.navigationReassertIntervalTicks) {
            villager.getNavigation().startMovingTo(task.moveTarget.getX() + 0.5D,
                    task.moveTarget.getY(), task.moveTarget.getZ() + 0.5D,
                    VillageExpansionConfig.navigationSpeed);
            task.lastBrainNavigationOverride = now;
        }
    }

    private static boolean isNight(long timeOfDay) {
        long dayTime = Math.floorMod(timeOfDay, 24_000L);
        return dayTime >= 13_000L && dayTime < 23_000L;
    }

    private static boolean nightWithAssignedHome(VillagerEntity villager) {
        return VillageExpansionConfig.sleepAtNightWhenHomeExists
                && isNight(villager.getWorld().getTimeOfDay())
                && villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME).isPresent();
    }

    private static boolean nearbyDanger(ServerWorld world, VillagerEntity villager) {
        if (villager.getAttacker() != null && villager.getAttacker().isAlive()) return true;
        Box dangerBox = villager.getBoundingBox().expand(VillageExpansionConfig.dangerRadius);
        return !world.getEntitiesByClass(HostileEntity.class, dangerBox,
                hostile -> hostile.isAlive() && !hostile.isRemoved()).isEmpty();
    }

    /** Let the vanilla Brain take over temporarily when the worker is in a critical state. */
    private static boolean healthEmergency(VillagerEntity villager) {
        return villager.isOnFire() || villager.isSubmergedInWater()
                || villager.getHealth() <= Math.max(4.0F, villager.getMaxHealth() * 0.25F);
    }

    private static final String DEBUG_LABEL_PREFIX = "[VE] ";

    private static void updateDebugLabel(VillagerEntity villager, BuilderTask task) {
        if (!VillageExpansionConfig.showBuilderTaskLabel) {
            clearDebugLabel(villager);
            return;
        }
        // Do not overwrite player-assigned names. Unnamed builders receive a
        // temporary visible name tag that is removed when their project ends.
        if (villager.getCustomName() != null && !villager.getCustomName().getString().startsWith(DEBUG_LABEL_PREFIX)) return;
        String label;
        if (task.sleepingAtNight) {
            label = "Dormindo";
        } else if (task.pausedUntil > villager.getWorld().getTime()) {
            label = "Pausado: segurança";
        } else if (task.planBlockedItem != null && task.planBlockedItem == task.pendingMaterialItem) {
            String itemName = Registries.ITEM.getId(task.planBlockedItem).getPath();
            label = "Sem plano viável: " + itemName;
        } else if (task.pendingMaterialItem != null && task.stage == Stage.GATHER_FROM_PROFESSIONAL) {
            String itemName = Registries.ITEM.getId(task.pendingMaterialItem).getPath();
            label = "Buscando profissional: " + itemName;
        } else if (task.pendingMaterialItem != null
                && task.lazyMaterialPlans.containsKey(task.pendingMaterialItem)
                && !task.lazyMaterialPlans.get(task.pendingMaterialItem).complete) {
            String itemName = Registries.ITEM.getId(task.pendingMaterialItem).getPath();
            MaterialPlanningJob job = task.lazyMaterialPlans.get(task.pendingMaterialItem);
            label = "Planejando: " + itemName + " (" + job.phaseLabel() + ")";
        } else if (task.pendingMaterialItem != null
                && (task.stage == Stage.GATHER_MATERIAL
                    || (task.stage == Stage.GATHER_STONE && task.resumeStage == Stage.GATHER_MATERIAL))) {
            String itemName = Registries.ITEM.getId(task.pendingMaterialItem).getPath();
            label = "Faltando: " + itemName + " x" + Math.max(1, task.pendingMaterialCount);
        } else {
            label = task.stage.debugName;
        }
        String fullLabel = DEBUG_LABEL_PREFIX + label;
        if (!fullLabel.equals(task.lastDebugLabel)
                || villager.getCustomName() == null
                || !fullLabel.equals(villager.getCustomName().getString())
                || !villager.isCustomNameVisible()) {
            villager.setCustomName(Text.literal(fullLabel));
            villager.setCustomNameVisible(true);
        }
        task.lastDebugLabel = fullLabel;
    }

    private static void clearDebugLabel(VillagerEntity villager) {
        if (villager.getCustomName() != null && villager.getCustomName().getString().startsWith(DEBUG_LABEL_PREFIX)) {
            villager.setCustomName(null);
            villager.setCustomNameVisible(false);
        }
    }

    private static void tickTask(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        long now = world.getTime();
        long elapsed = task.lastControllerUpdateAt == Long.MIN_VALUE ? 1L
                : Math.max(1L, Math.min(200L, now - task.lastControllerUpdateAt));
        task.elapsedControllerTicks = (int) elapsed;
        task.lastControllerUpdateAt = now;
        if (now >= task.nextDangerCheckAt) {
            task.cachedNearbyDanger = nearbyDanger(world, villager);
            task.nextDangerCheckAt = now + VillageExpansionConfig.dangerCheckIntervalTicks;
        }
        updateDebugLabel(villager, task);

        if (task.cachedNearbyDanger || (villager.getAttacker() != null && villager.getAttacker().isAlive())
                || healthEmergency(villager)) {
            // Stop mining/building and let vanilla panic, swimming and navigation
            // goals run while the villager is threatened, burning, drowning or
            // critically injured. The construction state remains saved.
            task.pausedUntil = Math.max(task.pausedUntil, now + VillageExpansionConfig.dangerPauseTicks);
            task.sleepingAtNight = false;
            clearBreakingProgress(world, villager, task);
            villager.getNavigation().stop();
            return;
        }
        if (now < task.pausedUntil) {
            clearBreakingProgress(world, villager, task);
            villager.getNavigation().stop();
            return; // Vanilla Brain is temporarily allowed to flee/seek safety.
        }

        if (nightWithAssignedHome(villager)) {
            task.sleepingAtNight = true;
            clearBreakingProgress(world, villager, task);
            villager.getNavigation().stop();
            return; // Vanilla schedule/Brain can take the villager to its own bed.
        }
        if (task.sleepingAtNight && isNight(world.getTimeOfDay())) {
            return;
        }
        task.sleepingAtNight = false;

        if (VillageExpansionConfig.builderParticlesEnabled
                && world.getTime() % VillageExpansionConfig.particleIntervalTicks == 0L) {
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    villager.getX(), villager.getY() + 1.75D, villager.getZ(),
                    2, 0.22D, 0.12D, 0.22D, 0.0D);
        }
        // Leaves decay naturally after the trunk is removed. Periodically collect
        // only sapling item drops and replant them; leaf blocks are never harvested.
        if (VillageExpansionConfig.replantSaplings && now >= task.nextSaplingCheckAt) {
            BlockPos origin = task.saplingSearchOrigin == null ? villager.getBlockPos() : task.saplingSearchOrigin;
            task.nextSaplingCheckAt = replantSaplings(world, villager, task, origin) ? now + 1200L : now + 100L;
        }

        if (task.actionTimer > 0) task.actionTimer = Math.max(0, task.actionTimer - task.elapsedControllerTicks);

        switch (task.stage) {
            case CLEAR_PATH -> tickClearPath(world, villager, task);
            case LEVEL_SITE -> tickLevelSite(world, villager, task);
            case BUILD_PATH -> tickBuildPath(world, villager, task);
            case GATHER_WOOD -> tickGatherWood(world, villager, task);
            case GATHER_DIRT -> tickGatherDirt(world, villager, task);
            case GATHER_STONE -> tickGatherStone(world, villager, task);
            case GATHER_MATERIAL -> tickGatherMaterial(world, villager, task);
            case GATHER_FROM_PROFESSIONAL -> tickGatherFromProfessional(world, villager, task);
            case SMELT_MATERIAL -> tickSmeltMaterial(world, villager, task);
            case MINE_TUNNEL -> tickMineTunnel(world, villager, task);
            case BUILD -> tickBuild(world, villager, task);
        }
    }

    private static void tryAssignBuilder(ServerWorld world, VillagerEntity villager) {
        if (!villager.isAlive() || villager.isBaby() || villager.isAiDisabled() || villager.hasCustomer()) return;
        // A villager with a real job is never converted into a builder.
        if (!villager.getVillagerData().getProfession().equals(VillagerProfession.NONE)) return;

        forgetInvalidHome(world, villager);

        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        // A hard dimension-wide cap prevents multiple nearby/founding villages from
        // multiplying the configured per-village builder allowance without bound.
        if (saved.getActiveTaskCount() >= VillageExpansionConfig.maxActiveBuildersPerDimension) {
            RETRY_AT.put(villager.getUuid(), world.getTime() + 600L);
            return;
        }
        VillageExpansionState.VillageRecord foundingVillage = saved.findFoundingVillageForFounder(villager.getUuid());
        // The founder keeps the bed assigned by its first home while returning
        // to build the founding profession house. This also lets it sleep at night.
        boolean founderNeedsFoundingProfession = foundingVillage != null
                && foundingVillage.needsFoundingProfessional();
        if (villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME).isPresent()
                && !founderNeedsFoundingProfession) return;
        if (world.getTime() < RETRY_AT.getOrDefault(villager.getUuid(), 0L)) return;
        VillageExpansionState.VillageRecord village;
        boolean createdSettlement = false;

        if (foundingVillage != null) {
            village = foundingVillage;
        } else {
            BlockPos anchor = findVillageAnchor(world, villager);
            if (anchor != null) {
                village = saved.findOrCreateVillage(anchor);
            } else {
                village = saved.findNearbyVillage(villager.getBlockPos(), 72.0D);
                if (village == null) {
                    village = saved.createFoundingVillage(villager.getBlockPos(), villager.getUuid());
                    createdSettlement = village.isFounding() && village.isFounder(villager.getUuid());
                }
            }
        }

        boolean foundingCanAcceptResidents = village.isFounding()
                && village.commonHouses() >= 1 && village.professionalHouses() >= 1 && village.hasBell();
        if (village.isFounding() && !village.isFounder(villager.getUuid())
                && !hasActiveFoundingProject(world, village) && !foundingCanAcceptResidents) {
            RETRY_AT.put(villager.getUuid(), world.getTime() + VillageExpansionConfig.retryDelayTicks);
            return;
        }

        int activeBuilders = countActiveBuilders(world, village.id());
        if (activeBuilders >= VillageExpansionConfig.maxBuildersPerVillage) {
            VillageExpansionMod.LOGGER.debug("Villager {} remains unemployed: village {} already has {}/{} active builders",
                    uuidShort(villager.getUuid()), village.id(), activeBuilders,
                    VillageExpansionConfig.maxBuildersPerVillage);
            RETRY_AT.put(villager.getUuid(), world.getTime() + 200L);
            return;
        }

        boolean professional = nextHouseIsProfessionalWithReservations(world, village);
        boolean prioritizeSmith = village.professionalHouses() == 0;
        HouseTemplate template = VillageTemplateLibrary.chooseHouse(
                world, village.center(), professional, prioritizeSmith).orElse(null);
        int sizeX = template == null ? 5 : template.size().getX();
        int sizeZ = template == null ? 5 : template.size().getZ();
        BlockPos site = findBuildSite(world, village, sizeX, sizeZ);
        if (site == null) {
            // findBuildSite advances a bounded rotating candidate window; retry
            // sooner so the remaining ring candidates are examined progressively.
            RETRY_AT.put(villager.getUuid(), world.getTime() + Math.max(40L, VillageExpansionConfig.assignmentScanInterval));
            return;
        }

        BuilderTask task = new BuilderTask(world.getRegistryKey(), village.id(), site, professional,
                village.isFounding(), villager.getEquippedStack(EquipmentSlot.MAINHAND).copy(),
                villager.getVillagerData(), template == null ? null : template.id().toString());
        task.footprintX = sizeX;
        task.footprintZ = sizeZ;
        task.targetProfessionId = template == null ? null : template.professionId();
        task.workstationPos = template == null || template.workstationOffset() == null
                ? null : site.add(template.workstationOffset());
        if (template != null) {
            for (VillageTemplateLibrary.TemplateBlock block : template.blocks()) {
                task.blueprint.add(new BlockPlacement(site.add(block.offset()), block.state()));
            }
            task.homeBed = template.worldBed(site);
        } else {
            task.blueprint = createBlueprint(site, professional,
                    task.foundingProject ? task.originalVillagerData.getProfession() : VillagerProfession.NONE);
            task.homeBed = site.add(0, 2, -1);
        }
        Map<Item, Integer> materialPlan = countBlueprintItems(task.blueprint);
        task.requiredWood = remainingTaggedMaterialRequirement(world, villager, materialPlan, ItemTags.LOGS);
        task.requiredStone = remainingTaggedMaterialRequirement(world, villager, materialPlan, ItemTags.STONE_TOOL_MATERIALS);
        task.wood = countInventoryItems(villager, ItemTags.LOGS);
        task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
        StringBuilder materialSummary = new StringBuilder();
        for (Map.Entry<Item, Integer> entry : materialPlan.entrySet()) {
            if (materialSummary.length() > 0) materialSummary.append(", ");
            materialSummary.append(Registries.ITEM.getId(entry.getKey())).append(" x").append(entry.getValue());
        }
        VillageExpansionMod.LOGGER.info("Builder {} planned {} exact item types for {} placements ({} estimated log blocks, {} estimated stone-material items): {}",
                uuidShort(villager.getUuid()), materialPlan.size(), task.blueprint.size(), task.requiredWood, task.requiredStone, materialSummary);
        BlockPos pathGoal = template == null ? site.add(0, 0, 3) : template.pathGoal(site);
        task.pathBlocks.addAll(createVillagePath(world, village.center(), pathGoal));
        task.levelOperations.addAll(createLevelOperations(world, site, sizeX, sizeZ));
        registerActiveTask(villager.getUuid(), task);
        saved.saveActiveTask(villager.getUuid(), writeTask(task));
        applyBuilderProfession(villager, task);
        villager.getNavigation().stop();

        if (createdSettlement) {
            VillageExpansionMod.LOGGER.info("Villager {} is founding a new settlement at {}",
                    uuidShort(villager.getUuid()), village.center());
        }
        VillageExpansionMod.LOGGER.info("Assigned {} biome template {} to villager {} at {}",
                professional ? "professional" : "ordinary", task.templateId, uuidShort(villager.getUuid()), site);
    }

    /** Forget stale HOME memories when the referenced bed is loaded but missing. */
    private static void forgetInvalidHome(ServerWorld world, VillagerEntity villager) {
        Optional<GlobalPos> home = villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME);
        if (home.isEmpty() || !home.get().dimension().equals(world.getRegistryKey())) return;
        BlockPos bedPos = home.get().pos();
        if (!isLoaded(world, bedPos)) return;
        if (!world.getBlockState(bedPos).isIn(BlockTags.BEDS)) {
            villager.getBrain().forget(MemoryModuleType.HOME);
            VillageExpansionMod.LOGGER.info("Villager {} lost its assigned bed at {}; it can now request a replacement home",
                    uuidShort(villager.getUuid()), bedPos);
        }
    }

    private static boolean hasActiveFoundingProject(ServerWorld world, VillageExpansionState.VillageRecord village) {
        for (Map.Entry<UUID, BuilderTask> entry : ACTIVE_TASKS.entrySet()) {
            BuilderTask active = entry.getValue();
            if (village.isFounder(entry.getKey()) && active.villageId == village.id()
                    && active.dimension.equals(world.getRegistryKey()) && active.foundingProject) return true;
        }
        return false;
    }

    private static void writePositionList(NbtCompound nbt, String key, List<BlockPos> positions) {
        NbtList list = new NbtList();
        for (BlockPos pos : positions) list.add(NbtLong.of(pos.asLong()));
        nbt.put(key, list);
    }

    private static List<BlockPos> readPositionList(NbtCompound nbt, String key) {
        List<BlockPos> positions = new ArrayList<>();
        if (!nbt.contains(key, NbtElement.LIST_TYPE)) return positions;
        NbtList list = nbt.getList(key, NbtElement.LONG_TYPE);
        for (int i = 0; i < list.size(); i++) {
            positions.add(BlockPos.fromLong(((NbtLong) list.get(i)).longValue()));
        }
        return positions;
    }

    private static void writeLevelOperations(NbtCompound nbt, List<LevelOperation> operations) {
        NbtList list = new NbtList();
        for (LevelOperation operation : operations) {
            NbtCompound item = new NbtCompound();
            item.putLong("pos", operation.pos().asLong());
            item.putBoolean("fill", operation.fill());
            list.add(item);
        }
        nbt.put("levelOperations", list);
    }

    private static List<LevelOperation> readLevelOperations(NbtCompound nbt) {
        List<LevelOperation> operations = new ArrayList<>();
        if (!nbt.contains("levelOperations", NbtElement.LIST_TYPE)) return operations;
        NbtList list = nbt.getList("levelOperations", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompound(i);
            operations.add(new LevelOperation(BlockPos.fromLong(item.getLong("pos")), item.getBoolean("fill")));
        }
        return operations;
    }

    private static NbtCompound writeTask(BuilderTask task) {
        NbtCompound nbt = new NbtCompound();
        nbt.putLong("village", task.villageId);
        nbt.putLong("site", task.site.asLong());
        nbt.putString("stage", task.stage.name());
        nbt.putBoolean("professional", task.professional);
        nbt.putBoolean("founding", task.foundingProject);
        nbt.putString("template", task.templateId == null ? "" : task.templateId);
        nbt.putString("profession", String.valueOf(Registries.VILLAGER_PROFESSION.getId(task.originalVillagerData.getProfession())));
        nbt.putInt("treeIndex", task.treeIndex);
        nbt.putInt("levelIndex", task.levelIndex);
        nbt.putInt("pathIndex", task.pathIndex);
        nbt.putInt("buildIndex", task.buildIndex);
        nbt.putInt("wood", task.wood);
        nbt.putInt("stone", task.stone);
        nbt.putInt("requiredWood", task.requiredWood);
        nbt.putInt("requiredStone", task.requiredStone);
        nbt.putInt("temporaryStoneRequirement", task.temporaryStoneRequirement);
        nbt.putInt("dirt", task.dirt);
        nbt.putInt("woodPiecesPlaced", task.woodPiecesPlaced);
        nbt.putInt("actionTimer", task.actionTimer);
        nbt.putLong("nextResourceSearch", task.nextResourceSearch);
        nbt.putLong("pausedUntil", task.pausedUntil);
        nbt.putBoolean("sleepingAtNight", task.sleepingAtNight);
        nbt.putBoolean("plotVegetationCleared", task.plotVegetationCleared);
        nbt.putInt("plotTreeScanCursor", task.plotTreeScanCursor);
        nbt.putBoolean("plotTreeScanComplete", task.plotTreeScanComplete);
        nbt.putInt("plotPlantScanCursor", task.plotPlantScanCursor);
        nbt.putInt("breakingTicks", task.breakingTicks);
        nbt.putInt("breakingDuration", task.breakingDuration);
        if (task.breakingPos != null) nbt.putLong("breakingPos", task.breakingPos.asLong());
        if (task.quarryCenter != null) nbt.putLong("quarryCenter", task.quarryCenter.asLong());
        if (task.mineEntrance != null) nbt.putLong("mineEntrance", task.mineEntrance.asLong());
        if (task.mineFace != null) nbt.putLong("mineFace", task.mineFace.asLong());
        nbt.putInt("mineDirection", task.mineDirection == null ? -1 : task.mineDirection.getId());
        nbt.putInt("mineSteps", task.mineSteps);
        nbt.putString("targetProfession", task.targetProfessionId == null ? "" : task.targetProfessionId.toString());
        if (task.workstationPos != null) nbt.putLong("workstationPos", task.workstationPos.asLong());
        nbt.putString("resumeStage", task.resumeStage == null ? "" : task.resumeStage.name());
        nbt.putString("materialReturnStage", task.materialReturnStage == null ? "" : task.materialReturnStage.name());
        nbt.putInt("requiredDirt", task.requiredDirt);
        nbt.putInt("resourceSearchFailures", task.resourceSearchFailures);
        nbt.putInt("stoneSearchFailures", task.stoneSearchFailures);
        nbt.putString("originalHandItem", Registries.ITEM.getId(task.originalMainHand.getItem()).toString());
        nbt.putInt("originalHandCount", task.originalMainHand.getCount());
        nbt.putInt("originalHandDamage", task.originalMainHand.getDamage());
        writePositionList(nbt, "treeTargets", task.treeTargets);
        writePositionList(nbt, "pathBlocks", task.pathBlocks);
        writePositionList(nbt, "currentTreeLogs", task.currentTreeLogs);
        writePositionList(nbt, "plotTreeTargets", task.plotTreeTargets);
        nbt.putInt("plotTreeIndex", task.plotTreeIndex);
        writePositionList(nbt, "currentTreeLeaves", task.currentTreeLeaves);
        writeLevelOperations(nbt, task.levelOperations);
        nbt.putInt("currentTreeLogIndex", task.currentTreeLogIndex);
        nbt.putString("currentTreeLogItem", task.currentTreeLogItem == null ? "" : Registries.ITEM.getId(task.currentTreeLogItem).toString());
        nbt.putInt("currentTreeLeafIndex", task.currentTreeLeafIndex);
        nbt.putBoolean("currentTreeLeavesScanned", task.currentTreeLeavesScanned);
        nbt.putInt("smeltTicksRemaining", task.smeltTicksRemaining);
        nbt.putInt("smeltFuelTicksRemaining", task.smeltFuelTicksRemaining);
        nbt.putInt("smeltOutputCount", task.smeltOutputCount);
        nbt.putString("pendingSmeltOutput", task.pendingSmeltOutput == null ? "" : Registries.ITEM.getId(task.pendingSmeltOutput).toString());
        nbt.putLong("nextMaterialSearch", task.nextMaterialSearch);
        if (task.professionalProviderUuid != null) nbt.putString("professionalProviderUuid", task.professionalProviderUuid.toString());
        nbt.putLong("professionalVisitStartedAt", task.professionalVisitStartedAt);
        if (task.rejectedProfessionalProviderUuid != null) nbt.putString("rejectedProfessionalProviderUuid", task.rejectedProfessionalProviderUuid.toString());
        if (task.rejectedProfessionalItem != null) nbt.putString("rejectedProfessionalItem", Registries.ITEM.getId(task.rejectedProfessionalItem).toString());
        nbt.putLong("rejectedProfessionalUntil", task.rejectedProfessionalUntil);
        if (task.pendingMaterialItem != null) nbt.putString("pendingMaterialItem", Registries.ITEM.getId(task.pendingMaterialItem).toString());
        if (task.planBlockedItem != null) nbt.putString("planBlockedItem", Registries.ITEM.getId(task.planBlockedItem).toString());
        nbt.putInt("pendingMaterialCount", task.pendingMaterialCount);
        if (task.deferredMaterialItem != null) {
            nbt.putString("deferredMaterialItem", Registries.ITEM.getId(task.deferredMaterialItem).toString());
            nbt.putInt("deferredMaterialCount", task.deferredMaterialCount);
        }
        NbtList goalStack = new NbtList();
        for (MaterialGoal goal : task.materialGoalStack) {
            NbtCompound entry = new NbtCompound();
            entry.putString("item", Registries.ITEM.getId(goal.item()).toString());
            entry.putInt("count", goal.count());
            goalStack.add(entry);
        }
        nbt.put("materialGoalStack", goalStack);
        if (task.saplingSearchOrigin != null) nbt.putLong("saplingSearchOrigin", task.saplingSearchOrigin.asLong());
        if (task.currentTreeBase != null) nbt.putLong("currentTreeBase", task.currentTreeBase.asLong());
        nbt.putBoolean("currentTreeScanComplete", task.currentTreeScanComplete);
        if (task.currentResource != null) {
            nbt.putLong("resourceBlock", task.currentResource.block().asLong());
            nbt.putLong("resourceStand", task.currentResource.stand().asLong());
        }
        if (task.currentMine != null) {
            nbt.putLong("currentMineRock", task.currentMine.rock().asLong());
            nbt.putLong("currentMineStand", task.currentMine.stand().asLong());
        }
        return nbt;
    }

    private static BuilderTask restoreTask(ServerWorld world, VillagerEntity villager, NbtCompound nbt) {
        try {
            long villageId = nbt.getLong("village");
            BlockPos site = BlockPos.fromLong(nbt.getLong("site"));
            boolean professional = nbt.getBoolean("professional");
            boolean founding = nbt.getBoolean("founding");
            Identifier professionId = Identifier.tryParse(nbt.getString("profession"));
            VillagerProfession originalProfession = professionId == null ? VillagerProfession.NONE
                    : Registries.VILLAGER_PROFESSION.getOrEmpty(professionId).orElse(VillagerProfession.NONE);
            VillagerData originalData = villager.getVillagerData().withProfession(originalProfession);
            ItemStack originalHand = villager.getEquippedStack(EquipmentSlot.MAINHAND).copy();
            if (nbt.contains("originalHandItem")) {
                Identifier handId = Identifier.tryParse(nbt.getString("originalHandItem"));
                int count = Math.max(0, nbt.getInt("originalHandCount"));
                if (handId != null && count > 0) {
                    Optional<Item> savedItem = Registries.ITEM.getOrEmpty(handId);
                    if (savedItem.isPresent()) {
                        originalHand = new ItemStack(savedItem.get(), Math.min(count, savedItem.get().getMaxCount()));
                        int damage = Math.max(0, nbt.getInt("originalHandDamage"));
                        if (damage > 0 && originalHand.isDamageable()) originalHand.setDamage(damage);
                    }
                } else if (count == 0) {
                    originalHand = ItemStack.EMPTY;
                }
            }
            String templateText = nbt.getString("template");
            BuilderTask task = new BuilderTask(world.getRegistryKey(), villageId, site, professional,
                    founding, originalHand, originalData,
                    templateText.isBlank() ? null : templateText);
            task.stage = Stage.valueOf(nbt.getString("stage"));
            String resumeStage = nbt.getString("resumeStage");
            if (!resumeStage.isBlank()) task.resumeStage = Stage.valueOf(resumeStage);
            String materialReturnStage = nbt.getString("materialReturnStage");
            if (!materialReturnStage.isBlank()) task.materialReturnStage = Stage.valueOf(materialReturnStage);
            task.requiredDirt = nbt.contains("requiredDirt") ? nbt.getInt("requiredDirt") : 0;
            task.resourceSearchFailures = nbt.getInt("resourceSearchFailures");
            task.stoneSearchFailures = nbt.contains("stoneSearchFailures")
                    ? Math.max(0, nbt.getInt("stoneSearchFailures")) : 0;
            task.targetProfessionId = Identifier.tryParse(nbt.getString("targetProfession"));
            if (nbt.contains("workstationPos", NbtElement.LONG_TYPE)) {
                task.workstationPos = BlockPos.fromLong(nbt.getLong("workstationPos"));
            }
            if (task.targetProfessionId == null && task.templateId != null) {
                Optional<HouseTemplate> professionTemplate = VillageTemplateLibrary.load(world, Identifier.tryParse(task.templateId));
                if (professionTemplate.isPresent()) task.targetProfessionId = professionTemplate.get().professionId();
            }
            task.treeIndex = nbt.getInt("treeIndex");
            task.levelIndex = nbt.getInt("levelIndex");
            task.pathIndex = nbt.getInt("pathIndex");
            task.buildIndex = nbt.getInt("buildIndex");
            task.wood = nbt.getInt("wood");
            task.stone = nbt.getInt("stone");
            task.requiredWood = nbt.contains("requiredWood") ? nbt.getInt("requiredWood") : VillageExpansionConfig.woodRequired;
            task.requiredStone = nbt.contains("requiredStone") ? nbt.getInt("requiredStone") : VillageExpansionConfig.stoneRequired;
            task.temporaryStoneRequirement = nbt.contains("temporaryStoneRequirement")
                    ? nbt.getInt("temporaryStoneRequirement") : -1;
            task.dirt = nbt.getInt("dirt");
            task.woodPiecesPlaced = nbt.getInt("woodPiecesPlaced");
            task.actionTimer = nbt.getInt("actionTimer");
            task.nextResourceSearch = nbt.getLong("nextResourceSearch");
            task.pausedUntil = nbt.getLong("pausedUntil");
            task.sleepingAtNight = nbt.getBoolean("sleepingAtNight");
            task.plotVegetationCleared = nbt.getBoolean("plotVegetationCleared");
            task.plotTreeScanCursor = Math.max(0, nbt.getInt("plotTreeScanCursor"));
            task.plotTreeScanComplete = nbt.getBoolean("plotTreeScanComplete");
            task.plotPlantScanCursor = Math.max(0, nbt.getInt("plotPlantScanCursor"));
            if (!task.plotVegetationCleared && !nbt.contains("plotTreeScanCursor")) {
                task.plotTreeScanCursor = 0;
                task.plotTreeScanComplete = false;
                task.plotPlantScanCursor = 0;
                task.plotTreeTargets.clear();
            }
            task.breakingTicks = nbt.getInt("breakingTicks");
            task.breakingDuration = nbt.getInt("breakingDuration");
            if (nbt.contains("breakingPos")) task.breakingPos = BlockPos.fromLong(nbt.getLong("breakingPos"));
            if (nbt.contains("quarryCenter")) task.quarryCenter = BlockPos.fromLong(nbt.getLong("quarryCenter"));
            if (nbt.contains("mineEntrance")) task.mineEntrance = BlockPos.fromLong(nbt.getLong("mineEntrance"));
            if (nbt.contains("mineFace")) task.mineFace = BlockPos.fromLong(nbt.getLong("mineFace"));
            int dir = nbt.getInt("mineDirection");
            if (dir >= 0) task.mineDirection = Direction.byId(dir);
            task.mineSteps = nbt.getInt("mineSteps");
            task.currentTreeLogIndex = nbt.getInt("currentTreeLogIndex");
            Identifier treeLogItemId = Identifier.tryParse(nbt.getString("currentTreeLogItem"));
            if (treeLogItemId != null) task.currentTreeLogItem = Registries.ITEM.getOrEmpty(treeLogItemId).orElse(null);
            task.currentTreeLeafIndex = nbt.getInt("currentTreeLeafIndex");
            task.currentTreeLeavesScanned = nbt.getBoolean("currentTreeLeavesScanned");
            task.smeltTicksRemaining = nbt.getInt("smeltTicksRemaining");
            task.smeltFuelTicksRemaining = nbt.getInt("smeltFuelTicksRemaining");
            task.smeltOutputCount = nbt.contains("smeltOutputCount") ? nbt.getInt("smeltOutputCount") : 1;
            Identifier smeltOutputId = Identifier.tryParse(nbt.getString("pendingSmeltOutput"));
            if (smeltOutputId != null) task.pendingSmeltOutput = Registries.ITEM.getOrEmpty(smeltOutputId).orElse(null);
            task.nextMaterialSearch = nbt.getLong("nextMaterialSearch");
            String providerUuid = nbt.getString("professionalProviderUuid");
            if (!providerUuid.isBlank()) {
                try { task.professionalProviderUuid = UUID.fromString(providerUuid); }
                catch (IllegalArgumentException ignored) { task.professionalProviderUuid = null; }
            }
            task.professionalVisitStartedAt = nbt.getLong("professionalVisitStartedAt");
            String rejectedProviderUuid = nbt.getString("rejectedProfessionalProviderUuid");
            if (!rejectedProviderUuid.isBlank()) {
                try { task.rejectedProfessionalProviderUuid = UUID.fromString(rejectedProviderUuid); }
                catch (IllegalArgumentException ignored) { task.rejectedProfessionalProviderUuid = null; }
            }
            Identifier rejectedItemId = Identifier.tryParse(nbt.getString("rejectedProfessionalItem"));
            if (rejectedItemId != null) task.rejectedProfessionalItem = Registries.ITEM.getOrEmpty(rejectedItemId).orElse(null);
            task.rejectedProfessionalUntil = nbt.getLong("rejectedProfessionalUntil");
            Identifier pendingMaterialId = Identifier.tryParse(nbt.getString("pendingMaterialItem"));
            if (pendingMaterialId != null) task.pendingMaterialItem = Registries.ITEM.getOrEmpty(pendingMaterialId).orElse(null);
            Identifier blockedMaterialId = Identifier.tryParse(nbt.getString("planBlockedItem"));
            if (blockedMaterialId != null) task.planBlockedItem = Registries.ITEM.getOrEmpty(blockedMaterialId).orElse(null);
            // Older task snapshots may have been waiting for grass_block as an
            // inventory item. Vanilla grass blocks drop dirt without Silk Touch,
            // so migrate that impossible request to its real harvest drop.
            if (task.pendingMaterialItem == Items.GRASS_BLOCK) {
                task.pendingMaterialItem = Items.DIRT;
            }
            task.pendingMaterialCount = nbt.contains("pendingMaterialCount")
                    ? Math.max(0, nbt.getInt("pendingMaterialCount")) : 1;
            // Older blueprints could accidentally count decorative plants (for example
            // short_grass) as house materials. These items are not structural resources;
            // discard the stale request and resume the saved construction stage.
            if (isNonStructuralDecorationItem(task.pendingMaterialItem)) {
                VillageExpansionMod.LOGGER.info(
                        "Clearing stale decorative material request {} for villager {} and resuming construction",
                        Registries.ITEM.getId(task.pendingMaterialItem), uuidShort(villager.getUuid()));
                task.pendingMaterialItem = null;
                task.pendingMaterialCount = 0;
                if (task.stage == Stage.GATHER_MATERIAL) {
                    task.stage = task.resumeStage == null ? Stage.BUILD : task.resumeStage;
                    task.resumeStage = null;
                }
            }
            Identifier deferredMaterialId = Identifier.tryParse(nbt.getString("deferredMaterialItem"));
            if (deferredMaterialId != null) {
                task.deferredMaterialItem = Registries.ITEM.getOrEmpty(deferredMaterialId).orElse(null);
                task.deferredMaterialCount = Math.max(1, nbt.getInt("deferredMaterialCount"));
            }
            if (nbt.contains("materialGoalStack", NbtElement.LIST_TYPE)) {
                NbtList goals = nbt.getList("materialGoalStack", NbtElement.COMPOUND_TYPE);
                for (int i = 0; i < goals.size(); i++) {
                    NbtCompound goal = goals.getCompound(i);
                    Identifier goalId = Identifier.tryParse(goal.getString("item"));
                    if (goalId == null) continue;
                    Item goalItem = Registries.ITEM.getOrEmpty(goalId).orElse(Items.AIR);
                    if (goalItem != Items.AIR) task.materialGoalStack.add(new MaterialGoal(goalItem,
                            Math.max(1, goal.getInt("count"))));
                }
            } else if (task.deferredMaterialItem != null) {
                // Migrate the single-parent deferred request used by 1.2.x saves.
                task.materialGoalStack.add(new MaterialGoal(task.deferredMaterialItem,
                        Math.max(1, task.deferredMaterialCount)));
                task.deferredMaterialItem = null;
                task.deferredMaterialCount = 0;
            }
            if (nbt.contains("saplingSearchOrigin", NbtElement.LONG_TYPE)) task.saplingSearchOrigin = BlockPos.fromLong(nbt.getLong("saplingSearchOrigin"));
            task.currentTreeLeaves.addAll(readPositionList(nbt, "currentTreeLeaves"));
            if (nbt.contains("currentTreeBase", NbtElement.LONG_TYPE)) {
                task.currentTreeBase = BlockPos.fromLong(nbt.getLong("currentTreeBase"));
            }
            task.treeTargets.addAll(readPositionList(nbt, "treeTargets"));
            task.pathBlocks.addAll(readPositionList(nbt, "pathBlocks"));
            task.currentTreeLogs.addAll(readPositionList(nbt, "currentTreeLogs"));
            task.currentTreeScanComplete = nbt.contains("currentTreeScanComplete")
                    ? nbt.getBoolean("currentTreeScanComplete") : !task.currentTreeLogs.isEmpty();
            if (!task.currentTreeScanComplete && task.currentTreeBase != null) {
                task.currentTreeLogs.clear();
                task.currentTreeLogIndex = 0;
            }
            task.plotTreeTargets.addAll(readPositionList(nbt, "plotTreeTargets"));
            task.plotTreeIndex = nbt.getInt("plotTreeIndex");
            task.levelOperations.addAll(readLevelOperations(nbt));

            // In 1.4.0 CLEAR_PATH cut trees along the entire future road before
            // the house existed. That made builders walk away from the plot and
            // spend long periods clearing a route. The road is now the final phase;
            // clear only the house footprint, build the house, then remove only
            // obstacles that actually intersect the finished road.
            if (task.stage == Stage.CLEAR_PATH) {
                task.stage = Stage.LEVEL_SITE;
                task.treeTargets.clear();
                task.treeIndex = 0;
                task.plotVegetationCleared = false;
                task.plotTreeScanCursor = 0;
                task.plotTreeScanComplete = false;
                task.plotPlantScanCursor = 0;
                task.plotTreeTargets.clear();
                task.plotTreeIndex = 0;
                task.currentTreeBase = null;
                task.currentTreeLogItem = null;
                task.currentTreeLogs.clear();
                task.currentTreeLogIndex = 0;
                task.currentTreeLeaves.clear();
                task.currentTreeLeafIndex = 0;
                task.currentTreeLeavesScanned = false;
                task.currentTreeScanComplete = false;
                task.currentTreeScanQueue.clear();
                task.currentTreeScanVisited.clear();
                task.currentTreeStandTarget = null;
                task.currentTreeStandPosition = null;
                task.currentTreeStandChecked = false;
                clearBreakingProgress(world, villager, task);
                task.moveTarget = null;
                task.requestedMoveTarget = null;
                VillageExpansionMod.LOGGER.info(
                        "Migrated builder {} from route-wide clearing to local plot preparation at {}",
                        uuidShort(villager.getUuid()), task.site);
            }
            if (nbt.contains("resourceBlock", NbtElement.LONG_TYPE)
                    && nbt.contains("resourceStand", NbtElement.LONG_TYPE)) {
                task.currentResource = new ResourceTarget(BlockPos.fromLong(nbt.getLong("resourceBlock")),
                        BlockPos.fromLong(nbt.getLong("resourceStand")));
            }
            if (nbt.contains("currentMineRock", NbtElement.LONG_TYPE)
                    && nbt.contains("currentMineStand", NbtElement.LONG_TYPE)) {
                task.currentMine = new MiningTarget(BlockPos.fromLong(nbt.getLong("currentMineRock")),
                        BlockPos.fromLong(nbt.getLong("currentMineStand")), List.of());
            }
            if (task.stage == Stage.GATHER_WOOD && task.currentTreeBase == null) {
                task.stage = Stage.BUILD;
                task.resumeStage = null;
            }

            VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                    VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
            VillageExpansionState.VillageRecord village = saved.getVillage(villageId);
            if (village == null) return null;
            int sx = 5, sz = 5;
            if (task.templateId != null) {
                Optional<HouseTemplate> template = VillageTemplateLibrary.load(world, Identifier.tryParse(task.templateId));
                if (template.isPresent()) {
                    HouseTemplate house = template.get();
                    sx = house.size().getX(); sz = house.size().getZ();
                    task.footprintX = sx; task.footprintZ = sz;
                    if (task.workstationPos == null && house.workstationOffset() != null) {
                        task.workstationPos = site.add(house.workstationOffset());
                    }
                    for (VillageTemplateLibrary.TemplateBlock block : house.blocks()) {
                        task.blueprint.add(new BlockPlacement(site.add(block.offset()), block.state()));
                    }
                    task.homeBed = house.worldBed(site);
                }
            }
            if (task.blueprint.isEmpty()) {
                task.blueprint = createBlueprint(site, professional, originalProfession);
                task.homeBed = site.add(0, 2, -1);
            }
            // Migrate in-progress 1.2.x tasks: the road is now the final phase,
            // so an old BUILD_PATH snapshot returns to the unfinished house.
            if (task.stage == Stage.BUILD_PATH && task.buildIndex < task.blueprint.size()) {
                task.stage = Stage.BUILD;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
            }
            BlockPos pathGoal = site.add(0, 0, 3);
            if (task.templateId != null) {
                Optional<HouseTemplate> restoredTemplate = VillageTemplateLibrary.load(world, Identifier.tryParse(task.templateId));
                if (restoredTemplate.isPresent()) pathGoal = restoredTemplate.get().pathGoal(site);
            }
            Map<Item, Integer> restoredMaterialPlan = countBlueprintItems(task.blueprint);
            task.requiredWood = remainingTaggedMaterialRequirement(world, villager, restoredMaterialPlan, ItemTags.LOGS);
            boolean restoringExactMiningRequest = task.resumeStage == Stage.GATHER_MATERIAL
                    && task.pendingMaterialItem != null
                    && (task.stage == Stage.GATHER_STONE || task.stage == Stage.MINE_TUNNEL);
            // The blueprint's cobblestone/stone budget must not overwrite a saved
            // exact request such as coal fuel or raw ore. The mining controller
            // compares the exact requested item count against this target.
            task.requiredStone = restoringExactMiningRequest
                    ? Math.max(1, task.pendingMaterialCount)
                    : remainingTaggedMaterialRequirement(world, villager, restoredMaterialPlan, ItemTags.STONE_TOOL_MATERIALS);
            task.wood = countInventoryItems(villager, ItemTags.LOGS);
            task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);

            // New 1.0.0 saves contain the exact work lists. Only older task snapshots
            // are regenerated, so a reload cannot silently change the route or work order.
            if (!nbt.contains("pathBlocks")) {
                task.pathBlocks.addAll(createVillagePath(world, village.center(), pathGoal));
            }
            if (!nbt.contains("levelOperations")) {
                task.levelOperations.addAll(createLevelOperations(world, site, sx, sz));
            }
            return task;
        } catch (Exception exception) {
            VillageExpansionMod.LOGGER.warn("Could not restore a saved construction task for villager {}", villager.getUuid(), exception);
            return null;
        }
    }

    private static void updateFoundingCompletion(ServerWorld world, VillageExpansionState saved) {
        for (long villageId : saved.getFoundingVillageIds()) {
            VillageExpansionState.VillageRecord village = saved.getVillage(villageId);
            if (village == null || !village.isFounding() || !village.hasBell()) continue;
            boolean foundVillager = false;
            boolean everyoneHasHome = true;
            BlockPos center = village.center();
            Box bounds = new Box(center.getX() - 64.0D, center.getY() - 32.0D, center.getZ() - 64.0D,
                    center.getX() + 64.0D, center.getY() + 32.0D, center.getZ() + 64.0D);
            for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, bounds, VillagerEntity::isAlive)) {
                foundVillager = true;
                Optional<GlobalPos> home = villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME);
                if (home.isEmpty() || !home.get().dimension().equals(world.getRegistryKey())
                        || (isLoaded(world, home.get().pos())
                            && !world.getBlockState(home.get().pos()).isIn(BlockTags.BEDS))) {
                    everyoneHasHome = false;
                    break;
                }
            }
            if (foundVillager && everyoneHasHome) saved.markVillageFounded(villageId);
        }
    }

    private static boolean buildMeetingPointIfNeeded(ServerWorld world, VillageExpansionState saved,
                                                     VillageExpansionState.VillageRecord village) {
        if (village == null) return false;
        if (village.hasBell()) return true;
        Optional<HouseTemplate> meeting = VillageTemplateLibrary.chooseMeetingPoint(world, village.center());
        if (meeting.isPresent() && meeting.get().bellOffset() != null) {
            HouseTemplate template = meeting.get();
            BlockPos origin = village.center();
            BlockPos bell = origin.add(template.bellOffset());
            BlockPos goldPedestal = bell.down();
            if (!isLoaded(world, bell) || !isLoaded(world, goldPedestal)
                    || hasLivingEntityInBlock(world, bell, null)
                    || hasLivingEntityInBlock(world, goldPedestal, null)) return false;
            for (VillageTemplateLibrary.TemplateBlock block : template.blocks()) {
                BlockPos target = origin.add(block.offset());
                if (isLoaded(world, target) && hasLivingEntityInBlock(world, target, null)) return false;
            }
            for (VillageTemplateLibrary.TemplateBlock block : template.blocks()) {
                BlockPos target = origin.add(block.offset());
                if (!isLoaded(world, target)) continue;
                BlockState current = world.getBlockState(target);
                if (current.isAir() || current.isReplaceable()) {
                    world.setBlockState(target, block.state(), Block.NOTIFY_ALL);
                }
            }
            if (world.getBlockState(bell).isOf(Blocks.BELL)) {
                world.setBlockState(goldPedestal, Blocks.GOLD_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
                saved.registerBell(village.id(), bell);
                return true;
            }
        }
        int surfaceY = findSurfaceY(world, village.center().getX(), village.center().getZ());
        if (surfaceY != Integer.MIN_VALUE) {
            BlockPos support = new BlockPos(village.center().getX(), surfaceY, village.center().getZ());
            BlockPos bell = support.up();
            if (isLoaded(world, support) && isLoaded(world, bell)
                    && !hasLivingEntityInBlock(world, support, null)
                    && !hasLivingEntityInBlock(world, bell, null)
                    && (world.getBlockState(bell).isAir() || world.getBlockState(bell).isReplaceable())
                    && world.getBlockState(support).isSolidBlock(world, support)) {
                world.setBlockState(support, Blocks.GOLD_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(bell, Blocks.BELL.getDefaultState(), Block.NOTIFY_ALL);
                saved.registerBell(village.id(), bell);
                return true;
            }
        }
        return village.hasBell();
    }

    private static int countActiveBuilders(ServerWorld world, long villageId) {
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        // Persistent task snapshots include workers whose chunks were not loaded
        // immediately after a server restart.
        return saved.getActiveTaskCountForVillage(villageId);
    }

    /**
     * Include already assigned projects when choosing the next house type. This
     * reserves the C-C-P sequence across concurrent builders instead of assigning
     * the same house type to every worker during one assignment scan.
     */
    private static boolean hasActiveFoundingProfessionalProject(
            ServerWorld world, VillageExpansionState.VillageRecord village) {
        RegistryKey<World> dimension = world.getRegistryKey();
        for (Map.Entry<UUID, BuilderTask> entry : ACTIVE_TASKS.entrySet()) {
            BuilderTask active = entry.getValue();
            if (village.isFounder(entry.getKey()) && active.villageId == village.id()
                    && active.dimension.equals(dimension) && active.foundingProject && active.professional) {
                return true;
            }
        }
        return false;
    }

    private static boolean nextHouseIsProfessionalWithReservations(
            ServerWorld world, VillageExpansionState.VillageRecord village) {
        if (village.isFounding()) {
            // The first eligible project is the founder's professional house.
            // Supporting builders are assigned ordinary houses while it runs.
            return village.needsFoundingProfessional()
                    && !hasActiveFoundingProfessionalProject(world, village);
        }

        int projectedCommon = village.commonHouses();
        int projectedProfessional = village.professionalHouses();
        RegistryKey<World> dimension = world.getRegistryKey();
        for (BuilderTask active : ACTIVE_TASKS.values()) {
            if (active.villageId != village.id() || !active.dimension.equals(dimension)) {
                continue;
            }
            if (active.professional) {
                projectedProfessional++;
            } else {
                projectedCommon++;
            }
        }
        return projectedCommon >= (projectedProfessional + 1) * 2;
    }

    private static boolean isBuildSiteReserved(ServerWorld world, long villageId, BlockPos candidate) {
        RegistryKey<World> dimension = world.getRegistryKey();
        for (BuilderTask active : ACTIVE_TASKS.values()) {
            if (active.villageId == villageId && active.dimension.equals(dimension)
                    && active.site.getSquaredDistance(candidate) < 10.0D * 10.0D) return true;
        }
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        for (BlockPos reserved : saved.getActiveTaskSitesForVillage(villageId)) {
            if (reserved.getSquaredDistance(candidate) < 10.0D * 10.0D) return true;
        }
        return false;
    }

    /**
     * Resolve the existing settlement from a nearby villager's HOME memory.
     * A bell or an existing bed is used as a fallback for partially initialized villages.
     */
    private static BlockPos findVillageAnchor(ServerWorld world, VillagerEntity subject) {
        // Use Minecraft's entity spatial index instead of iterating every loaded
        // entity in the world. If no nearby villager has a HOME memory, the caller
        // checks the mod's village registry or creates a new founding settlement.
        Box nearby = subject.getBoundingBox().expand(64.0D);
        BlockPos best = null;
        double bestDistance = 64.0D * 64.0D;
        for (VillagerEntity other : world.getEntitiesByClass(VillagerEntity.class, nearby,
                candidate -> candidate != subject && candidate.isAlive())) {
            Optional<GlobalPos> home = other.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME);
            if (home.isEmpty() || !home.get().dimension().equals(world.getRegistryKey())) continue;
            BlockPos pos = home.get().pos();
            double distance = pos.getSquaredDistance(subject.getBlockPos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best == null ? null : best.toImmutable();
    }

    private static BlockPos findBuildSite(ServerWorld world, VillageExpansionState.VillageRecord village,
                                           int sizeX, int sizeZ) {
        BlockPos center = village.center();
        WorldRuntime runtime = WORLD_RUNTIMES.computeIfAbsent(world.getRegistryKey(), ignored -> new WorldRuntime());
        int minRadius = VillageExpansionConfig.houseSearchMinRadius;
        int maxRadius = VillageExpansionConfig.houseSearchMaxRadius;
        int radiusStep = Math.max(2, VillageExpansionConfig.houseSearchStep);
        int radiusBands = Math.max(1, (int) Math.ceil((maxRadius - minRadius) / (double) radiusStep) + 1);
        final int angleCount = 16;
        int totalCandidates = radiusBands * angleCount;
        int cursor = Math.floorMod(runtime.siteSearchCursor, totalCandidates);
        // Keep the search local to the village and inspect only four candidate
        // footprints per assignment slice. Uneven plots are accepted when their
        // cut/fill work remains within the configured terraform budget.
        int attempts = Math.min(4, totalCandidates);
        for (int checked = 0; checked < attempts; checked++) {
            int candidateIndex = (cursor + checked) % totalCandidates;
            int radiusIndex = candidateIndex / angleCount;
            int angleStep = candidateIndex % angleCount;
            int radius = Math.min(maxRadius, minRadius + radiusIndex * radiusStep);
            double angle = (Math.PI * 2.0D * angleStep) / angleCount;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos candidate = createViableSite(world, x, z, sizeX, sizeZ);
            if (candidate == null || village.isNearExistingHouse(candidate, VillageExpansionConfig.houseSpacing)
                    || village.isNearMine(candidate, VillageExpansionConfig.mineExclusionRadius)
                    || isBuildSiteReserved(world, village.id(), candidate)) continue;
            runtime.siteSearchCursor = (candidateIndex + 1) % totalCandidates;
            return candidate;
        }
        runtime.siteSearchCursor = (cursor + attempts) % totalCandidates;
        return null;
    }

    private static BlockPos createViableSite(ServerWorld world, int centerX, int centerZ, int sizeX, int sizeZ) {
        int halfX = sizeX / 2;
        int halfZ = sizeZ / 2;
        List<Integer> groundHeights = new ArrayList<>(sizeX * sizeZ);
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int x = centerX - halfX; x < centerX - halfX + sizeX; x++) {
            for (int z = centerZ - halfZ; z < centerZ - halfZ + sizeZ; z++) {
                BlockPos sample = new BlockPos(x, world.getTopY() - 1, z);
                if (!isLoaded(world, sample)) return null;
                int groundY = findSurfaceYFast(world, x, z, 24);
                if (groundY == Integer.MIN_VALUE) return null;
                BlockPos above = new BlockPos(x, groundY + 1, z);
                if (!world.getFluidState(above).isEmpty()) return null;
                groundHeights.add(groundY);
                minY = Math.min(minY, groundY);
                maxY = Math.max(maxY, groundY);
            }
        }
        // Do not require a naturally flat footprint. The median is the target
        // floor; the configured variation and operation budgets decide whether
        // the builder can safely adapt the surrounding terrain.
        groundHeights.sort(Integer::compareTo);
        int baseY = groundHeights.get(groundHeights.size() / 2);
        int minAllowedY = baseY - VillageExpansionConfig.maxSiteTerrainVariation;
        int maxAllowedY = baseY + VillageExpansionConfig.maxSiteTerrainVariation;
        if (minY < minAllowedY || maxY > maxAllowedY) return null;

        // Reject only plots that would require excessive earthwork. Small slopes,
        // shallow dips and one/two-block rises are handled by LEVEL_SITE itself.
        int terraformBlocks = 0;
        for (int height : groundHeights) {
            terraformBlocks += Math.abs(height - baseY);
            if (terraformBlocks > VillageExpansionConfig.maxSiteTerraformBlocks) return null;
        }

        for (int x = centerX - halfX; x < centerX - halfX + sizeX; x++) {
            for (int z = centerZ - halfZ; z < centerZ - halfZ + sizeZ; z++) {
                int surfaceY = findSurfaceYFast(world, x, z, 24);
                if (surfaceY == Integer.MIN_VALUE) return null;
                // Columns below the median are filled; columns above it are cut.
                // Natural dirt/stone in the final headroom is also removable, and
                // is counted against the terraform budget instead of rejecting the plot.
                int clearanceStart = Math.max(surfaceY, baseY) + 1;
                for (int y = clearanceStart; y <= baseY + 10; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!world.getFluidState(pos).isEmpty()) return null;
                    if (state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || isPlant(state)) continue;
                    if (state.isIn(BlockTags.LOGS) && hasNearbyLeaves(world, pos)) continue;
                    if (state.hasBlockEntity()) return null;
                    if (isTerrain(state)) {
                        if (++terraformBlocks > VillageExpansionConfig.maxSiteTerraformBlocks) return null;
                        continue;
                    }
                    // Never demolish player/modded structures to make a house plot.
                    if (state.isSolidBlock(world, pos)) return null;
                }
            }
        }
        return new BlockPos(centerX - halfX, baseY, centerZ - halfZ);
    }

    /**
     * Starts at the closest existing village dirt path. If the settlement has no
     * dirt path yet, the route begins at the terrain near its registered centre.
     */
    private static List<BlockPos> createVillagePath(ServerWorld world, BlockPos villageCenter, BlockPos goal) {
        BlockPos start = findNearestVillagePath(world, villageCenter, goal);
        if (start == null) {
            int startY = findSurfaceY(world, villageCenter.getX(), villageCenter.getZ());
            if (startY == Integer.MIN_VALUE) {
                return List.of();
            }
            start = new BlockPos(villageCenter.getX(), startY, villageCenter.getZ());
        }

        int endX = goal.getX();
        int endZ = goal.getZ();
        int endY = findPathSurfaceY(world, endX, endZ);
        if (endY == Integer.MIN_VALUE) {
            return List.of();
        }
        BlockPos end = new BlockPos(endX, endY, endZ);
        List<BlockPos> centreLine = findPathAStar(world, start, end);
        if (centreLine.isEmpty()) return centreLine;
        // Widen the route to three blocks. The perpendicular axis follows the
        // local direction so turns do not produce long diagonal protrusions.
        LinkedHashMap<Long, BlockPos> widened = new LinkedHashMap<>();
        for (int i = 0; i < centreLine.size(); i++) {
            BlockPos point = centreLine.get(i);
            BlockPos previous = centreLine.get(Math.max(0, i - 1));
            BlockPos next = centreLine.get(Math.min(centreLine.size() - 1, i + 1));
            int dx = Integer.compare(next.getX(), previous.getX());
            int dz = Integer.compare(next.getZ(), previous.getZ());
            int px = dz != 0 ? 1 : 0;
            int pz = dx != 0 ? 1 : 0;
            for (int lane = -1; lane <= 1; lane++) {
                BlockPos wide = point.add(px * lane, 0, pz * lane);
                int y = findPathSurfaceY(world, wide.getX(), wide.getZ());
                if (y == Integer.MIN_VALUE) continue;
                BlockPos surface = new BlockPos(wide.getX(), y, wide.getZ());
                widened.putIfAbsent(surface.asLong(), surface);
            }
        }
        return new ArrayList<>(widened.values());
    }

    private record RoadPathNode(BlockPos pos, double pathCost, double estimatedTotalCost) {}

    /**
     * Bounded four-way A* for the access road. This is deliberately small and uses
     * Minecraft's heightmap-backed terrain lookup; it never searches unloaded chunks
     * or expands beyond maxPathSearchNodes. Vanilla navigation remains responsible
     * for moving the villager between work positions.
     */
    private static List<BlockPos> findPathAStar(ServerWorld world, BlockPos start, BlockPos goal) {
        if (start.getX() == goal.getX() && start.getZ() == goal.getZ()) return List.of(start.toImmutable());
        int maxNodes = Math.max(100, Math.min(600, VillageExpansionConfig.maxPathSearchNodes));
        PriorityQueue<RoadPathNode> open = new PriorityQueue<>(Comparator.comparingDouble(RoadPathNode::estimatedTotalCost));
        Map<BlockPos, Double> bestCost = new HashMap<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        BlockPos startPos = start.toImmutable();
        BlockPos goalPos = goal.toImmutable();
        bestCost.put(startPos, 0.0D);
        open.add(new RoadPathNode(startPos, 0.0D, roadHeuristic(startPos, goalPos)));
        BlockPos reached = null;
        int expanded = 0;
        final int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!open.isEmpty() && expanded < maxNodes) {
            RoadPathNode currentNode = open.poll();
            BlockPos current = currentNode.pos();
            if (!closed.add(current)) continue;
            expanded++;
            if (current.getX() == goalPos.getX() && current.getZ() == goalPos.getZ()) {
                reached = current;
                break;
            }

            for (int[] direction : directions) {
                int nextX = current.getX() + direction[0];
                int nextZ = current.getZ() + direction[1];
                int nextY = findPathSurfaceY(world, nextX, nextZ);
                if (nextY == Integer.MIN_VALUE || Math.abs(nextY - current.getY()) > 2) continue;
                BlockPos next = new BlockPos(nextX, nextY, nextZ);
                if (closed.contains(next)) continue;

                BlockPos feet = next.up();
                BlockPos head = next.up(2);
                if (!isLoaded(world, next) || !isLoaded(world, feet) || !isLoaded(world, head)) continue;
                BlockState feetState = world.getBlockState(feet);
                BlockState headState = world.getBlockState(head);
                if (feetState.hasBlockEntity() || headState.hasBlockEntity()) continue;
                if ((!feetState.isAir() && !feetState.isReplaceable()
                        && !feetState.isIn(BlockTags.LOGS) && !feetState.isIn(BlockTags.LEAVES) && !isPlant(feetState))
                        || (!headState.isAir() && !headState.isReplaceable()
                        && !headState.isIn(BlockTags.LOGS) && !headState.isIn(BlockTags.LEAVES) && !isPlant(headState))) continue;
                if (world.getFluidState(feet).isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                        || world.getFluidState(head).isIn(net.minecraft.registry.tag.FluidTags.LAVA)) continue;

                double stepCost = 1.0D + Math.abs(nextY - current.getY()) * 2.0D;
                if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty()) stepCost += 6.0D;
                if (feetState.isIn(BlockTags.LOGS) || feetState.isIn(BlockTags.LEAVES)
                        || headState.isIn(BlockTags.LOGS) || headState.isIn(BlockTags.LEAVES)) stepCost += 2.0D;
                double candidateCost = currentNode.pathCost() + stepCost;
                if (candidateCost >= bestCost.getOrDefault(next, Double.MAX_VALUE)) continue;
                bestCost.put(next, candidateCost);
                previous.put(next, current);
                open.add(new RoadPathNode(next, candidateCost, candidateCost + roadHeuristic(next, goalPos)));
            }
        }
        if (reached == null) return List.of();
        List<BlockPos> path = new ArrayList<>();
        BlockPos cursor = reached;
        path.add(cursor);
        while (!cursor.equals(startPos)) {
            cursor = previous.get(cursor);
            if (cursor == null || path.size() > maxNodes + 1) return List.of();
            path.add(cursor);
        }
        Collections.reverse(path);
        return path;
    }

    private static double roadHeuristic(BlockPos from, BlockPos to) {
        return Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ())
                + Math.abs(from.getY() - to.getY()) * 1.5D;
    }

    private static int findPathSurfaceY(ServerWorld world, int x, int z) {
        // Reuse Minecraft's maintained heightmap and inspect at most 24 blocks below it.
        return findSurfaceYFast(world, x, z, 24);
    }

    private static BlockPos findNearestVillagePath(ServerWorld world, BlockPos center, BlockPos site) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = 28;
        int diameter = radius * 2 + 1;
        // Bounded probes keep road connection discovery cheap even in dense modpacks.
        for (int sample = 0; sample < 32; sample++) {
            int x = center.getX() + world.random.nextInt(diameter) - radius;
            int z = center.getZ() + world.random.nextInt(diameter) - radius;
            if (!world.isChunkLoaded(new ChunkPos(x >> 4, z >> 4).toLong())) continue;
            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            for (int y = topY; y >= Math.max(world.getBottomY(), topY - 4); y--) {
                BlockPos candidate = new BlockPos(x, y, z);
                if (!world.getBlockState(candidate).isOf(Blocks.DIRT_PATH)) continue;
                double distance = candidate.getSquaredDistance(site);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate.toImmutable();
                }
                break;
            }
        }
        return best;
    }

    private static List<BlockPos> findTreesAlongPath(ServerWorld world, List<BlockPos> path, BlockPos site,
                                                     int sizeX, int sizeZ) {
        Set<BlockPos> unique = new HashSet<>();
        // Sample at most ~24 route positions. The widened path itself is only 3 blocks;
        // the 3x3 neighborhood is sampled around each anchor to catch obstructing trunks.
        int stride = Math.max(1, path.size() / 24);
        for (int i = 0; i < path.size(); i += stride) {
            BlockPos pathBlock = path.get(i);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    addTreeBasesAtColumn(world, pathBlock.getX() + dx, pathBlock.getZ() + dz, unique);
                }
            }
        }
        if (!path.isEmpty()) {
            BlockPos last = path.get(path.size() - 1);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    addTreeBasesAtColumn(world, last.getX() + dx, last.getZ() + dz, unique);
                }
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
        int stride = Math.max(1, steps / 24);
        for (int i = 0; i <= steps; i += stride) {
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
        int groundY = findSurfaceYFast(world, x, z, 24);
        if (groundY == Integer.MIN_VALUE) return;
        // Natural tree roots start directly above terrain. Searching arbitrary log
        // columns allowed house pillars and wooden walls to be mistaken for trees.
        BlockPos root = new BlockPos(x, groundY + 1, z);
        if (isNaturalTreeRoot(world, root) && !isProtectedByNearbyBuilding(world, root)) output.add(root.toImmutable());
    }

    private static boolean isNaturalTreeRoot(ServerWorld world, BlockPos root) {
        if (!isLoaded(world, root) || !world.getBlockState(root).isIn(BlockTags.LOGS)) return false;
        BlockState soil = world.getBlockState(root.down());
        boolean naturalSoil = soil.isIn(BlockTags.DIRT) || soil.isOf(Blocks.GRASS_BLOCK)
                || soil.isOf(Blocks.MYCELIUM) || soil.isOf(Blocks.PODZOL)
                || soil.isOf(Blocks.MOSS_BLOCK) || soil.isOf(Blocks.ROOTED_DIRT)
                || soil.isOf(Blocks.MUD);
        if (!naturalSoil || world.getBlockState(root.down()).isIn(BlockTags.LOGS)) return false;
        // Vanilla tree trunks have a vertical stem at the soil line. Requiring three
        // connected vertical log blocks and a canopy above avoids treating most wooden
        // posts, fences, house walls and decorative logs as harvestable trees.
        if (!world.getBlockState(root.up()).isIn(BlockTags.LOGS)
                || !world.getBlockState(root.up(2)).isIn(BlockTags.LOGS)) return false;
        // Sparse canopy probes: the former 9x13x9 volume caused over a thousand
        // block reads for every possible tree root. Trees missed by this quick test
        // can still be found by later expanded resource scans.
        for (int dx = -4; dx <= 4; dx += 2) {
            for (int dy = 2; dy <= 14; dy += 2) {
                for (int dz = -4; dz <= 4; dz += 2) {
                    BlockPos canopy = root.add(dx, dy, dz);
                    if (!isLoaded(world, canopy)) continue;
                    BlockState leafState = world.getBlockState(canopy);
                    if (!leafState.isIn(BlockTags.LEAVES)) continue;
                    if (leafState.contains(LeavesBlock.PERSISTENT)
                            && leafState.get(LeavesBlock.PERSISTENT)) continue;
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockPos findNaturalTreeRoot(ServerWorld world, BlockPos anyLog) {
        BlockPos cursor = anyLog.toImmutable();
        int safety = 0;
        while (world.getBlockState(cursor.down()).isIn(BlockTags.LOGS) && safety++ < 32) cursor = cursor.down();
        return isNaturalTreeRoot(world, cursor) && !isProtectedByNearbyBuilding(world, cursor) ? cursor : null;
    }

    /** Conservatively protects wood near recognizable houses, workstations and containers. */
    private static boolean isProtectedByNearbyBuilding(ServerWorld world, BlockPos root) {
        // Check a compact 7x8x7 neighborhood rather than the old 13x11x13 volume.
        // Beds/doors and block entities at house edges are still detected.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 6; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos check = root.add(dx, dy, dz);
                    if (!isLoaded(world, check)) continue;
                    BlockState state = world.getBlockState(check);
                    if (state.getBlock() instanceof DoorBlock
                            || state.getBlock() instanceof BedBlock
                            || state.hasBlockEntity()) return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNearbyLeaves(ServerWorld world, BlockPos origin) {
        // Sparse canopy test used only as a safety hint while preparing a site.
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dy = -2; dy <= 10; dy += 2) {
                for (int dz = -3; dz <= 3; dz += 3) {
                    BlockPos check = origin.add(dx, dy, dz);
                    if (isLoaded(world, check) && world.getBlockState(check).isIn(BlockTags.LEAVES)) return true;
                }
            }
        }
        return false;
    }

    private static List<LevelOperation> createLevelOperations(ServerWorld world, BlockPos site, int sizeX, int sizeZ) {
        List<LevelOperation> cuts = new ArrayList<>();
        List<LevelOperation> clearanceCuts = new ArrayList<>();
        List<LevelOperation> fills = new ArrayList<>();

        for (int x = site.getX(); x < site.getX() + sizeX; x++) {
            for (int z = site.getZ(); z < site.getZ() + sizeZ; z++) {
                int surfaceY = findSurfaceYFast(world, x, z, 24);
                if (surfaceY == Integer.MIN_VALUE) continue;
                if (surfaceY > site.getY()) {
                    for (int y = surfaceY; y > site.getY(); y--) {
                        cuts.add(new LevelOperation(new BlockPos(x, y, z), false));
                    }
                } else if (surfaceY < site.getY()) {
                    for (int y = surfaceY + 1; y <= site.getY(); y++) {
                        fills.add(new LevelOperation(new BlockPos(x, y, z), true));
                    }
                }

                // In low columns, remove natural dirt/stone protruding into the
                // future house volume. Tree logs/leaves and plants are handled by
                // the separate tree/vegetation pass. Existing structures are never
                // added here; site validation rejects block entities and solids.
                int clearanceStart = Math.max(surfaceY, site.getY()) + 1;
                for (int y = clearanceStart; y <= site.getY() + 10; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (isTerrain(state) && !state.isAir()) {
                        clearanceCuts.add(new LevelOperation(pos, false));
                    }
                }
            }
        }

        // Remove obstructing natural terrain first, then level high cells, then fill
        // low cells using dirt collected from the cuts or nearby natural sources.
        cuts.addAll(clearanceCuts);
        cuts.addAll(fills);
        if (cuts.size() > VillageExpansionConfig.maxSiteTerraformBlocks) return new ArrayList<>();
        return cuts;
    }

    /**
     * Bounded surface lookup for wide resource scans. The old findSurfaceY walked
     * from build height to bedrock for every X/Z column, which multiplied into
     * hundreds of thousands of block reads per material search in modpacks.
     */
    private static int findSurfaceYFast(ServerWorld world, int x, int z, int maxDepth) {
        if (!world.isChunkLoaded(new ChunkPos(x >> 4, z >> 4).toLong())) return Integer.MIN_VALUE;
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int minY = Math.max(world.getBottomY(), top - Math.max(4, maxDepth));
        for (int y = top; y >= minY; y--) {
            if (isTerrain(world.getBlockState(new BlockPos(x, y, z)))) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static int findSurfaceY(ServerWorld world, int x, int z) {
        // Heightmaps are maintained by Minecraft during chunk updates. Searching
        // downward from WORLD_SURFACE to bedrock for every column multiplied into
        // tens of thousands of redundant block reads per builder. A bounded local
        // check around MOTION_BLOCKING_NO_LEAVES is enough for our terrain tasks.
        return findSurfaceYFast(world, x, z, 40);
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
        // First try the eight adjacent cells, then a compact radius-two ring.
        // This prevents a dense tree/undergrowth patch from leaving CLEAR_PATH stuck
        // forever because no immediately adjacent standing cell is free.
        for (int radius = 1; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = workBlock.getX() + dx;
                    int z = workBlock.getZ() + dz;
                    int groundY = findSurfaceYFast(world, x, z, 24);
                    if (groundY == Integer.MIN_VALUE) continue;
                    BlockPos stand = new BlockPos(x, groundY + 1, z);
                    BlockPos head = stand.up();
                    BlockPos floor = stand.down();
                    if (!isLoaded(world, stand) || !isLoaded(world, head) || !isLoaded(world, floor)
                            || !world.getBlockState(stand).isAir() || !world.getBlockState(head).isAir()
                            || !world.getBlockState(floor).isSolidBlock(world, floor)
                            || !world.getFluidState(stand).isEmpty()) continue;
                    double distance = villager.squaredDistanceTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = stand;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static boolean isValidPlotWorkStand(ServerWorld world, BlockPos stand) {
        if (!isLoaded(world, stand) || !isLoaded(world, stand.up()) || !isLoaded(world, stand.down())) return false;
        return world.getBlockState(stand).isAir() && world.getBlockState(stand.up()).isAir()
                && world.getBlockState(stand.down()).isSolidBlock(world, stand.down())
                && world.getFluidState(stand).isEmpty();
    }

    /** Finds an accessible working cell just outside the selected house footprint. */
    private static BlockPos findPlotWorkStand(ServerWorld world, BuilderTask task, VillagerEntity villager) {
        int midX = task.site.getX() + task.footprintX / 2;
        int midZ = task.site.getZ() + task.footprintZ / 2;
        int[][] probes = {
                {midX, task.site.getZ() - 1}, {midX, task.site.getZ() + task.footprintZ},
                {task.site.getX() - 1, midZ}, {task.site.getX() + task.footprintX, midZ},
                {task.site.getX() - 1, task.site.getZ() - 1},
                {task.site.getX() + task.footprintX, task.site.getZ() - 1},
                {task.site.getX() - 1, task.site.getZ() + task.footprintZ},
                {task.site.getX() + task.footprintX, task.site.getZ() + task.footprintZ},
                {midX, task.site.getZ() - 2}, {midX, task.site.getZ() + task.footprintZ + 1},
                {task.site.getX() - 2, midZ}, {task.site.getX() + task.footprintX + 1, midZ},
                {task.site.getX() - 2, task.site.getZ() - 2},
                {task.site.getX() + task.footprintX + 1, task.site.getZ() - 2},
                {task.site.getX() - 2, task.site.getZ() + task.footprintZ + 1},
                {task.site.getX() + task.footprintX + 1, task.site.getZ() + task.footprintZ + 1}
        };
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int[] probe : probes) {
            int x = probe[0];
            int z = probe[1];
            int groundY = findSurfaceYFast(world, x, z, 24);
            if (groundY == Integer.MIN_VALUE) continue;
            BlockPos stand = new BlockPos(x, groundY + 1, z);
            BlockPos floor = stand.down();
            if (task.rejectedLevelWorkStands.contains(stand.asLong())
                    || !isLoaded(world, stand) || !isLoaded(world, stand.up())
                    || !world.getBlockState(stand).isAir() || !world.getBlockState(stand.up()).isAir()
                    || !world.getBlockState(floor).isSolidBlock(world, floor)
                    || !world.getFluidState(stand).isEmpty()) continue;
            double distance = villager.squaredDistanceTo(x + 0.5D, stand.getY(), z + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = stand;
            }
        }
        return best;
    }

    private static void tickClearPath(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        // Compatibility guard for snapshots created before 1.4.1. Never clear the
        // whole future road ahead of the house: that route can be many blocks long.
        // The house is built first; tickBuildPath handles each actual road obstacle.
        task.treeTargets.clear();
        task.treeIndex = 0;
        villager.getNavigation().stop();
        task.stage = Stage.LEVEL_SITE;
        task.moveTarget = null;
        task.requestedMoveTarget = null;
    }

    /** Breaks only the connected logs of one confirmed natural tree. Leaves decay naturally.
     * The connected-log discovery is incremental: at most 32 queue nodes are examined
     * per controller slice, so a huge modded tree cannot monopolize the server thread. */
    private static boolean tickCutTree(ServerWorld world, VillagerEntity villager, BuilderTask task, BlockPos base) {
        return tickCutTree(world, villager, task, base, false);
    }

    private static boolean tickCutTree(ServerWorld world, VillagerEntity villager, BuilderTask task,
                                       BlockPos base, boolean rootPrevalidated) {
        BlockPos naturalRoot;
        if (task.currentTreeBase != null && task.currentTreeBase.equals(base)) {
            naturalRoot = task.currentTreeBase;
        } else if (rootPrevalidated) {
            naturalRoot = base;
        } else {
            if (!acquireSpatialSearchPermit(world)) return false;
            naturalRoot = findNaturalTreeRoot(world, base);
            if (naturalRoot == null) {
                clearBreakingProgress(world, villager, task);
                return true;
            }
        }
        base = naturalRoot;
        if (task.currentTreeBase == null || !task.currentTreeBase.equals(base)) {
            clearBreakingProgress(world, villager, task);
            task.currentTreeBase = base.toImmutable();
            task.currentTreeLogItem = world.getBlockState(base).getBlock().asItem();
            task.currentTreeLogs.clear();
            task.currentTreeLeaves.clear();
            task.currentTreeLogIndex = 0;
            task.currentTreeLeafIndex = 0;
            task.currentTreeLeavesScanned = false;
            task.currentTreeScanComplete = false;
            task.currentTreeScanQueue.clear();
            task.currentTreeScanVisited.clear();
            task.currentTreeScanQueue.add(base.toImmutable());
            task.currentTreeScanVisited.add(base.asLong());
        } else if (!task.currentTreeScanComplete && task.currentTreeScanQueue.isEmpty()
                && task.currentTreeScanVisited.isEmpty()) {
            // The BFS queue is transient. If a save/reload happened during discovery,
            // restart this bounded scan from the remembered root instead of guessing.
            task.currentTreeLogs.clear();
            task.currentTreeLogIndex = 0;
            task.currentTreeScanQueue.add(base.toImmutable());
            task.currentTreeScanVisited.add(base.asLong());
        }

        if (!task.currentTreeScanComplete) {
            int scanBudget = 32;
            while (scanBudget-- > 0 && !task.currentTreeScanQueue.isEmpty()
                    && task.currentTreeLogs.size() < 512) {
                BlockPos current = task.currentTreeScanQueue.removeFirst();
                if (!isLoaded(world, current) || !world.getBlockState(current).isIn(BlockTags.LOGS)) continue;
                task.currentTreeLogs.add(current.toImmutable());
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos neighbor = current.add(dx, dy, dz);
                            if (Math.abs(neighbor.getX() - base.getX()) > 6
                                    || Math.abs(neighbor.getZ() - base.getZ()) > 6
                                    || neighbor.getY() < base.getY() || neighbor.getY() - base.getY() > 32) continue;
                            long key = neighbor.asLong();
                            if (task.currentTreeScanVisited.add(key) && isLoaded(world, neighbor)
                                    && world.getBlockState(neighbor).isIn(BlockTags.LOGS)) {
                                task.currentTreeScanQueue.addLast(neighbor);
                            }
                        }
                    }
                }
            }
            if (task.currentTreeScanQueue.isEmpty() || task.currentTreeLogs.size() >= 512) {
                task.currentTreeScanComplete = true;
                final BlockPos completedTreeOrigin = base;
                task.currentTreeLogs.sort(Comparator.comparingInt(BlockPos::getY)
                        .thenComparingDouble(pos -> pos.getSquaredDistance(completedTreeOrigin)));
                task.currentTreeScanQueue.clear();
                task.currentTreeScanVisited.clear();
            } else {
                return false; // Yield to vanilla AI and other builders.
            }
        }

        while (task.currentTreeLogIndex < task.currentTreeLogs.size()) {
            BlockPos log = task.currentTreeLogs.get(task.currentTreeLogIndex);
            if (!world.getBlockState(log).isIn(BlockTags.LOGS)) {
                task.currentTreeLogIndex++;
                task.currentTreeStandTarget = null;
                task.currentTreeStandPosition = null;
                task.currentTreeStandChecked = false;
                continue;
            }
            if (task.currentTreeStandTarget == null || !task.currentTreeStandTarget.equals(log)
                    || task.currentTreeStandChecked && task.currentTreeStandPosition == null) {
                task.currentTreeStandTarget = log.toImmutable();
                task.currentTreeStandPosition = findStandingPosition(world, log, villager);
                if (task.currentTreeStandPosition == null) {
                    task.currentTreeStandPosition = findStandingPosition(world, base, villager);
                }
                task.currentTreeStandChecked = true;
            }
            BlockPos stand = task.currentTreeStandPosition;
            if (stand == null || task.failedNavigationTarget != null && task.failedNavigationTarget.equals(stand)) {
                // One inaccessible log must not strand the complete house project.
                // Skip only this log after adjacent/radius-two standing positions fail.
                task.currentTreeLogIndex++;
                task.currentTreeStandTarget = null;
                task.currentTreeStandPosition = null;
                task.currentTreeStandChecked = false;
                task.failedNavigationTarget = null;
                task.requestedMoveTarget = null;
                clearBreakingProgress(world, villager, task);
                VillageExpansionMod.LOGGER.warn("Builder {} skipped an unreachable tree log at {} and will continue clearing the plot",
                        uuidShort(villager.getUuid()), log);
                return false;
            }
            if (!moveNear(villager, task, stand, VillageExpansionConfig.navigationSpeed, true)) return false;
            equipBestTool(villager, ToolKind.AXE);
            if (!tickBreakBlock(world, villager, task, log, ToolKind.AXE, ItemTags.LOGS)) return false;
            task.currentTreeLogIndex++;
            task.currentTreeStandTarget = null;
            task.currentTreeStandPosition = null;
            task.currentTreeStandChecked = false;
            task.wood = countInventoryItems(villager, ItemTags.LOGS);
            return false;
        }

        task.saplingSearchOrigin = base.toImmutable();
        task.nextSaplingCheckAt = world.getTime() + 100L;
        task.currentTreeBase = null;
        task.currentTreeLogItem = null;
        task.currentTreeLogs.clear();
        task.currentTreeLeaves.clear();
        task.currentTreeLogIndex = 0;
        task.currentTreeLeafIndex = 0;
        task.currentTreeLeavesScanned = false;
        task.currentTreeScanComplete = false;
        task.currentTreeScanQueue.clear();
        task.currentTreeScanVisited.clear();
        task.currentTreeStandTarget = null;
        task.currentTreeStandPosition = null;
        task.currentTreeStandChecked = false;
        clearBreakingProgress(world, villager, task);
        task.wood = countInventoryItems(villager, ItemTags.LOGS);
        return true;
    }

    private static boolean replantSaplings(ServerWorld world, VillagerEntity villager, BuilderTask task, BlockPos origin) {
        if (!VillageExpansionConfig.replantSaplings) return true;
        if (!acquireSpatialSearchPermit(world)) return false;

        // Plant at most one sapling per pass. The next sapling is handled by a later
        // throttled pass; this prevents stacks of drops from starting dozens of searches.
        int plantingBudget = 1;
        for (int slot = 0; slot < villager.getInventory().size() && plantingBudget > 0; slot++) {
            ItemStack stack = villager.getInventory().getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.SAPLINGS)) continue;
            Block saplingBlock = getSaplingBlock(stack.getItem());
            if (saplingBlock == Blocks.AIR) continue;
            while (!stack.isEmpty() && plantingBudget > 0) {
                BlockPos plantingSpot = findSaplingPlantingSpot(world, task, origin, saplingBlock);
                if (plantingSpot == null || !world.setBlockState(plantingSpot,
                        saplingBlock.getDefaultState(), Block.NOTIFY_ALL)) break;
                stack.decrement(1);
                plantingBudget--;
                villager.getInventory().markDirty();
                world.playSound(null, plantingSpot, SoundEvents.BLOCK_GRASS_PLACE,
                        SoundCategory.BLOCKS, 0.35F, 1.1F);
            }
        }
        if (plantingBudget <= 0) return true;

        // If the Villager's eight inventory slots were full when a leaf dropped a
        // sapling, plant one nearby sapling item entity instead of leaving it on the ground.
        int radius = VillageExpansionConfig.saplingPlantRadius;
        Box dropArea = new Box(origin.getX() - radius, origin.getY() - 16.0D, origin.getZ() - radius,
                origin.getX() + radius + 1.0D, origin.getY() + 18.0D, origin.getZ() + radius + 1.0D);
        for (ItemEntity entity : world.getEntitiesByClass(ItemEntity.class, dropArea,
                item -> !item.isRemoved() && item.getStack().isIn(ItemTags.SAPLINGS))) {
            ItemStack stack = entity.getStack();
            Block saplingBlock = getSaplingBlock(stack.getItem());
            if (saplingBlock == Blocks.AIR) continue;
            boolean plantedAny = false;
            while (!stack.isEmpty() && plantingBudget > 0) {
                BlockPos plantingSpot = findSaplingPlantingSpot(world, task, origin, saplingBlock);
                if (plantingSpot == null || !world.setBlockState(plantingSpot,
                        saplingBlock.getDefaultState(), Block.NOTIFY_ALL)) break;
                stack.decrement(1);
                plantingBudget--;
                plantedAny = true;
                world.playSound(null, plantingSpot, SoundEvents.BLOCK_GRASS_PLACE,
                        SoundCategory.BLOCKS, 0.35F, 1.1F);
            }
            if (stack.isEmpty()) entity.discard();
            else if (plantedAny) entity.setStack(stack);
            if (plantingBudget <= 0) break;
        }
        return true;
    }

    private static Block getSaplingBlock(Item saplingItem) {
        Block block = Registries.BLOCK.get(Registries.ITEM.getId(saplingItem));
        return block != Blocks.AIR && block.asItem().equals(saplingItem) ? block : Blocks.AIR;
    }

    /**
     * Finds one planting position using a fixed sample budget. The previous implementation
     * scanned a 65x65 grid twice and repeatedly searched the height of every column;
     * 20 builders could therefore issue tens of thousands of block reads at once.
     */
    private static BlockPos findSaplingPlantingSpot(ServerWorld world, BuilderTask task,
                                                    BlockPos origin, Block saplingBlock) {
        int radius = VillageExpansionConfig.saplingPlantRadius;
        int spacing = VillageExpansionConfig.saplingMinimumSpacing;
        int attempts = 12;
        for (int attempt = 0; attempt < attempts; attempt++) {
            int x = origin.getX() + world.random.nextInt(radius * 2 + 1) - radius;
            int z = origin.getZ() + world.random.nextInt(radius * 2 + 1) - radius;
            int groundY = findSurfaceYFast(world, x, z, 16);
            if (groundY == Integer.MIN_VALUE) continue;
            BlockPos ground = new BlockPos(x, groundY, z);
            BlockPos plantPos = ground.up();
            BlockState groundState = world.getBlockState(ground);
            if (!(groundState.isIn(BlockTags.DIRT) || groundState.isOf(Blocks.GRASS_BLOCK)
                    || groundState.isOf(Blocks.PODZOL) || groundState.isOf(Blocks.COARSE_DIRT))
                    || !isLoaded(world, plantPos) || !world.getBlockState(plantPos).isAir()
                    || world.getLightLevel(plantPos) < 8
                    || !saplingBlock.getDefaultState().canPlaceAt(world, plantPos)) continue;
            if (x >= task.site.getX() - 2 && x < task.site.getX() + task.footprintX + 2
                    && z >= task.site.getZ() - 2 && z < task.site.getZ() + task.footprintZ + 2) continue;

            boolean nearPath = false;
            for (BlockPos path : task.pathBlocks) {
                if (Math.abs(path.getX() - x) <= 1 && Math.abs(path.getZ() - z) <= 1) {
                    nearPath = true;
                    break;
                }
            }
            if (nearPath) continue;

            // Check only eight perimeter samples for existing saplings. This is intentionally
            // conservative and bounded; exact global spacing is less valuable than zero-cost scans.
            boolean tooClose = false;
            for (int[] offset : SAPLING_SPACING_OFFSETS) {
                int sx = x + offset[0];
                int sz = z + offset[1];
                int saplingGroundY = findSurfaceYFast(world, sx, sz, 16);
                if (saplingGroundY == Integer.MIN_VALUE) continue;
                BlockPos possibleSapling = new BlockPos(sx, saplingGroundY + 1, sz);
                if (isLoaded(world, possibleSapling)
                        && world.getBlockState(possibleSapling).isIn(BlockTags.SAPLINGS)) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) return plantPos.toImmutable();
        }
        return null;
    }

    private enum ToolKind { AXE, PICKAXE, SHOVEL }

    private static void equipBestTool(VillagerEntity villager, ToolKind kind) {
        ItemStack current = villager.getEquippedStack(EquipmentSlot.MAINHAND);
        int bestTier = toolTier(current.getItem(), kind);
        ItemStack best = bestTier >= 0 ? current.copy() : ItemStack.EMPTY;
        int bestSlot = -1;
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            ItemStack candidate = villager.getInventory().getStack(slot);
            int tier = candidate.isEmpty() ? -1 : toolTier(candidate.getItem(), kind);
            if (tier > bestTier) { bestTier = tier; best = candidate.copy(); bestSlot = slot; }
        }
        if (bestTier < 0) {
            // A worker must craft or obtain its tools through the objective planner.
            // Never fabricate a wooden tool just because a mining task needs one.
            if (!current.isEmpty()) villager.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        } else if (!villager.getEquippedStack(EquipmentSlot.MAINHAND).isOf(best.getItem())) {
            if (bestSlot >= 0) {
                villager.getInventory().getStack(bestSlot).decrement(1);
                villager.getInventory().markDirty();
            }
            villager.equipStack(EquipmentSlot.MAINHAND, best);
        }
    }

    private static int toolTier(Item item, ToolKind kind) {
        return switch (kind) {
            case AXE -> item == Items.NETHERITE_AXE ? 5 : item == Items.DIAMOND_AXE ? 4
                    : item == Items.IRON_AXE ? 3 : item == Items.GOLDEN_AXE ? 2
                    : item == Items.STONE_AXE ? 1 : item == Items.WOODEN_AXE ? 0 : -1;
            // Gold pickaxes mine like wooden pickaxes (they cannot harvest iron ore).
            case PICKAXE -> item == Items.NETHERITE_PICKAXE ? 5 : item == Items.DIAMOND_PICKAXE ? 4
                    : item == Items.IRON_PICKAXE ? 3 : item == Items.STONE_PICKAXE ? 1
                    : (item == Items.WOODEN_PICKAXE || item == Items.GOLDEN_PICKAXE) ? 0 : -1;
            case SHOVEL -> item == Items.NETHERITE_SHOVEL ? 5 : item == Items.DIAMOND_SHOVEL ? 4
                    : item == Items.IRON_SHOVEL ? 3 : item == Items.GOLDEN_SHOVEL ? 2
                    : item == Items.STONE_SHOVEL ? 1 : item == Items.WOODEN_SHOVEL ? 0 : -1;
        };
    }

    private static int countSpecificItem(VillagerEntity villager, Item item) {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            ItemStack stack = villager.getInventory().getStack(slot);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private static boolean consumeItems(VillagerEntity villager, Item item, int amount) {
        for (int slot = 0; slot < villager.getInventory().size() && amount > 0; slot++) {
            ItemStack stack = villager.getInventory().getStack(slot);
            if (stack.isOf(item)) {
                int used = Math.min(amount, stack.getCount());
                stack.decrement(used);
                amount -= used;
            }
        }
        villager.getInventory().markDirty();
        return amount == 0;
    }

    private static boolean tickBreakBlock(ServerWorld world, VillagerEntity villager, BuilderTask task,
                                          BlockPos pos, ToolKind kind, TagKey<Item> acceptedDrops) {
        return tickBreakBlock(world, villager, task, pos, kind, acceptedDrops, null);
    }

    /**
     * Mines a block using the builder's actual held tool for loot-table evaluation.
     * World.breakBlock(pos, true, entity) has no tool parameter; using it directly
     * makes tool-gated loot (stone -> cobblestone, ores -> raw ore, etc.) evaluate
     * as though the block had been broken with an empty hand. Remove the block
     * without automatic drops, then invoke Block.dropStacks with the captured tool.
     */
    private static boolean tickBreakBlock(ServerWorld world, VillagerEntity villager, BuilderTask task,
                                          BlockPos pos, ToolKind kind, TagKey<Item> acceptedDrops,
                                          Item acceptedItem) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) { clearBreakingProgress(world, villager, task); return true; }
        if (!isLoaded(world, pos)) return false;
        float hardness = state.getHardness(world, pos);
        if (hardness < 0.0F) return false;
        if (task.breakingPos == null || !task.breakingPos.equals(pos)) {
            clearBreakingProgress(world, villager, task);
            task.breakingPos = pos.toImmutable();
            ItemStack tool = villager.getEquippedStack(EquipmentSlot.MAINHAND);
            float miningSpeed = Math.max(0.1F, tool.getMiningSpeedMultiplier(state));
            // Vanilla player mining uses 30 ticks for a harvestable block and
            // 100 otherwise. Keep the same relationship and expose a small
            // global pacing multiplier through blockBreakBaseTicks.
            float vanillaTicks = hardness * (tool.isSuitableFor(state) ? 30.0F : 100.0F) / miningSpeed;
            float pacingScale = VillageExpansionConfig.blockBreakBaseTicks / 30.0F;
            task.breakingDuration = Math.max(1, (int) Math.ceil(vanillaTicks * pacingScale));
            task.breakingTicks = 0;
        }
        task.breakingTicks = Math.min(task.breakingDuration,
                task.breakingTicks + Math.max(1, task.elapsedControllerTicks));
        if (task.breakingTicks % 5 < Math.max(1, task.elapsedControllerTicks)) villager.swingHand(Hand.MAIN_HAND);
        int progress = Math.min(9, (int) ((task.breakingTicks / (float) task.breakingDuration) * 10.0F));
        world.setBlockBreakingInfo(villager.getId(), pos, progress);
        if (task.breakingTicks < task.breakingDuration) return false;

        world.setBlockBreakingInfo(villager.getId(), pos, -1);
        ItemStack usedTool = villager.getEquippedStack(EquipmentSlot.MAINHAND);
        ItemStack toolForLoot = usedTool.copy();
        net.minecraft.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
        boolean broken = world.breakBlock(pos, false, villager);
        if (broken) {
            // The state, block entity and tool are captured before removal so
            // vanilla loot conditions (correct tool/tier, Silk Touch, Fortune)
            // are evaluated against the same tool the builder used to mine.
            Block.dropStacks(state, world, pos, blockEntity, villager, toolForLoot);
            if (acceptedItem != null) {
                collectSpecificDrops(world, villager, pos, acceptedItem);
            } else {
                collectDrops(world, villager, pos, acceptedDrops);
            }
            if (usedTool.isDamageable()) usedTool.damage(1, villager, EquipmentSlot.MAINHAND);
        }
        task.breakingPos = null;
        task.breakingTicks = 0;
        task.breakingDuration = 0;
        return broken;
    }

    private static void clearBreakingProgress(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.breakingPos != null) world.setBlockBreakingInfo(villager.getId(), task.breakingPos, -1);
        task.breakingPos = null;
        task.breakingTicks = 0;
        task.breakingDuration = 0;
    }

    private static void tickLevelSite(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (!task.plotVegetationCleared) {
            if (!clearPlotVegetation(world, villager, task)) return;
            task.plotVegetationCleared = true;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
        }

        if (task.plotTreeIndex < task.plotTreeTargets.size()) {
            BlockPos treeBase = task.plotTreeTargets.get(task.plotTreeIndex);
            if (tickCutTree(world, villager, task, treeBase, true)) {
                task.plotTreeIndex++;
                task.currentResource = null;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
            }
            return;
        }

        // A naturally suitable footprint does not need a pointless trip to the
        // northwest corner of the plot. Go directly to BUILD when no cut/fill work exists.
        if (task.levelIndex >= task.levelOperations.size()) {
            villager.getNavigation().stop();
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            task.stage = Stage.BUILD;
            return;
        }

        if (task.levelWorkStand == null || !isValidPlotWorkStand(world, task.levelWorkStand)) {
            task.levelWorkStand = findPlotWorkStand(world, task, villager);
        }
        if (task.levelWorkStand == null && task.levelIndex < task.levelOperations.size()) {
            // If every perimeter point is obstructed, work from a safe cell beside
            // the exact next cut/fill operation rather than abandoning a workable plot.
            task.levelWorkStand = findStandingPosition(
                    world, task.levelOperations.get(task.levelIndex).pos(), villager);
        }
        if (task.levelWorkStand == null) {
            // Last local fallback: search around the footprint centre, not along the
            // village road or in an expanding world-wide radius.
            BlockPos plotCenter = new BlockPos(task.site.getX() + task.footprintX / 2,
                    task.site.getY(), task.site.getZ() + task.footprintZ / 2);
            task.levelWorkStand = findStandingPosition(world, plotCenter, villager);
        }
        if (task.levelWorkStand == null) {
            task.levelStandSearchFailures++;
            if (task.levelStandSearchFailures >= 3) {
                // Last-resort graceful degradation: do not immobilize the builder.
                // The placement stage still performs per-block obstruction checks.
                VillageExpansionMod.LOGGER.warn("Builder {} could not find a safe leveling stand near {}; skipping remaining optional terrain adjustment",
                        uuidShort(villager.getUuid()), task.site);
                task.levelIndex = task.levelOperations.size();
                task.stage = Stage.BUILD;
            }
            return;
        }
        if (!moveNear(villager, task, task.levelWorkStand, VillageExpansionConfig.navigationSpeed, true)) {
            if (task.failedNavigationTarget != null && task.failedNavigationTarget.equals(task.levelWorkStand)) {
                task.rejectedLevelWorkStands.add(task.levelWorkStand.asLong());
                task.levelWorkStand = null;
                task.failedNavigationTarget = null;
                task.requestedMoveTarget = null;
            }
            return;
        }
        task.levelStandSearchFailures = 0;

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
                        // Never retry the same fill forever. Search for a dirt block, then resume this exact operation.
                        task.levelIndex--;
                        task.resumeStage = Stage.LEVEL_SITE;
                        task.requiredDirt = countInventoryItems(villager, ItemTags.DIRT) + 1;
                        task.resourceSearchFailures = 0;
                        task.stage = Stage.GATHER_DIRT;
                        task.nextResourceSearch = world.getTime();
                        task.moveTarget = null;
                        task.requestedMoveTarget = null;
                        return;
                    }
                    world.setBlockState(operation.pos, fillState, Block.NOTIFY_ALL);
                }
                task.dirt = countInventoryItems(villager, ItemTags.DIRT);
                task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
            } else if (!current.isAir()) {
                // A player/modded block may have appeared after site selection. Never
                // turn a terrain-preparation operation into permission to demolish it.
                if (current.hasBlockEntity() || !isTerrain(current)) {
                    task.actionTimer = VillageExpansionConfig.buildBlockDelayTicks;
                    return;
                }
                ToolKind kind = current.isIn(BlockTags.BASE_STONE_OVERWORLD) ? ToolKind.PICKAXE : ToolKind.SHOVEL;
                equipBestTool(villager, kind);
                TagKey<Item> drops = current.isIn(BlockTags.BASE_STONE_OVERWORLD) ? ItemTags.STONE_TOOL_MATERIALS : ItemTags.DIRT;
                if (!tickBreakBlock(world, villager, task, operation.pos, kind, drops)) {
                    task.levelIndex--;
                    return;
                }
                task.dirt = countInventoryItems(villager, ItemTags.DIRT);
                task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
            }
            task.actionTimer = VillageExpansionConfig.buildBlockDelayTicks;
            return;
        }

        villager.getNavigation().stop();
        task.moveTarget = null;
        task.requestedMoveTarget = null;
        // Finish the house before spending time on its access road.
        task.stage = Stage.BUILD;
    }

    private static void tickBuildPath(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.currentTreeBase != null) {
            if (tickCutTree(world, villager, task, task.currentTreeBase)) {
                task.wood = countInventoryItems(villager, ItemTags.LOGS);
                task.currentTreeBase = null;
                task.currentResource = null;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
            }
            return;
        }
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
            task.requestedMoveTarget = null;
            completeProject(world, villager, task);
            return;
        }

        BlockPos ground = task.pathBlocks.get(task.pathIndex);
        BlockPos stand = ground.up();
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos above = ground.up(dy);
            BlockState obstacle = world.getBlockState(above);
            if (obstacle.isIn(BlockTags.LEAVES)) {
                equipBestTool(villager, ToolKind.AXE);
                if (tickBreakBlock(world, villager, task, above, ToolKind.AXE, ItemTags.SAPLINGS)) {
                    task.saplingSearchOrigin = above.toImmutable();
                    replantSaplings(world, villager, task, above);
                    task.actionTimer = VillageExpansionConfig.buildBlockDelayTicks;
                }
                return;
            }
            if (isPlant(obstacle)) {
                equipBestTool(villager, ToolKind.SHOVEL);
                if (tickBreakBlock(world, villager, task, above, ToolKind.SHOVEL, null)) {
                    task.actionTimer = VillageExpansionConfig.buildBlockDelayTicks;
                }
                return;
            }
            if (obstacle.isIn(BlockTags.LOGS)) {
                BlockPos treeBase = findNaturalTreeRoot(world, above);
                if (treeBase != null) {
                    BlockPos treeStand = findStandingPosition(world, treeBase, villager);
                    if (treeStand == null) {
                        // Keep the road task moving when a natural tree cannot be
                        // approached safely; do not wander away from the house forever.
                        task.pathIndex++;
                        task.moveTarget = null;
                        task.requestedMoveTarget = null;
                        task.failedNavigationTarget = null;
                        return;
                    }
                    if (!moveNear(villager, task, treeStand, VillageExpansionConfig.navigationSpeed, true)) {
                        if (treeStand.equals(task.failedNavigationTarget)) {
                            task.pathIndex++;
                            task.failedNavigationTarget = null;
                            task.moveTarget = null;
                            task.requestedMoveTarget = null;
                        }
                        return;
                    }
                    if (tickCutTree(world, villager, task, treeBase)) {
                        task.wood = countInventoryItems(villager, ItemTags.LOGS);
                        task.moveTarget = null;
                        task.requestedMoveTarget = null;
                    }
                    return;
                }
                // Wooden player/mod structures are never valid tree resources.
                task.pathIndex++;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
                return;
            }
            if (!obstacle.isAir()) {
                task.pathIndex++;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
                return;
            }
        }

        if (!moveNear(villager, task, stand, VillageExpansionConfig.navigationSpeed)) {
            return;
        }
        if (task.actionTimer > 0) {
            return;
        }

        BlockState currentFloor = world.getBlockState(ground);
        if (currentFloor.isOf(Blocks.DIRT_PATH)) {
            task.pathIndex++;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
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
        task.requestedMoveTarget = null;
        task.actionTimer = VillageExpansionConfig.buildBlockDelayTicks;
    }

    private static boolean treeCanopyOverlapsPlot(ServerWorld world, BlockPos root, BuilderTask task) {
        int minY = Math.max(task.site.getY() + 1, root.getY() + 2);
        int maxY = Math.min(task.site.getY() + 10, root.getY() + 14);
        for (int x = task.site.getX(); x < task.site.getX() + task.footprintX; x++) {
            for (int z = task.site.getZ(); z < task.site.getZ() + task.footprintZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos check = new BlockPos(x, y, z);
                    if (isLoaded(world, check) && world.getBlockState(check).isIn(BlockTags.LEAVES)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Incrementally discovers trees and clears only plants inside the actual footprint.
     * Four X/Z columns or 32 plant cells are processed per task slice, and cursors are
     * persisted so saving/reloading does not restart the scan.
     */
    private static boolean clearPlotVegetation(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        BlockPos site = task.site;
        int treeWidth = task.footprintX + 8;
        int treeDepth = task.footprintZ + 8;
        int treeColumns = treeWidth * treeDepth;

        if (!task.plotTreeScanComplete) {
            if (task.plotTreeScanCursor == 0) task.plotTreeTargets.clear();
            int budget = 16;
            while (budget-- > 0 && task.plotTreeScanCursor < treeColumns) {
                int index = task.plotTreeScanCursor++;
                int x = site.getX() - 4 + index / treeDepth;
                int z = site.getZ() - 4 + index % treeDepth;
                int groundY = findSurfaceYFast(world, x, z, 24);
                if (groundY == Integer.MIN_VALUE) continue;
                BlockPos possibleRoot = new BlockPos(x, groundY + 1, z);
                if (!world.getBlockState(possibleRoot).isIn(BlockTags.LOGS)) continue;
                BlockPos root = findNaturalTreeRoot(world, possibleRoot);
                if (root != null && treeCanopyOverlapsPlot(world, root, task)
                        && !task.plotTreeTargets.contains(root)) {
                    task.plotTreeTargets.add(root.toImmutable());
                }
            }
            if (task.plotTreeScanCursor < treeColumns) return false;
            task.plotTreeTargets.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(site)));
            task.plotTreeIndex = 0;
            task.plotTreeScanComplete = true;
            return false; // Separate tree-discovery and plant-clear phases.
        }

        int plantColumns = task.footprintX * task.footprintZ;
        int totalPlantCells = plantColumns * 10;
        int plantBudget = 32;
        while (plantBudget-- > 0 && task.plotPlantScanCursor < totalPlantCells) {
            int index = task.plotPlantScanCursor++;
            int column = index / 10;
            int yOffset = index % 10;
            int x = site.getX() + column / task.footprintZ;
            int z = site.getZ() + column % task.footprintZ;
            BlockPos pos = new BlockPos(x, site.getY() + 1 + yOffset, z);
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) continue;
            if (isPlant(state)) {
                // These are optional weeds/flowers in a house footprint, not resources
                // reserved for crafting. Remove without loot to avoid item-entity spam.
                world.breakBlock(pos, false, villager);
            }
        }
        return task.plotPlantScanCursor >= totalPlantCells;
    }

    private static void tickGatherDirt(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        task.dirt = countInventoryItems(villager, ItemTags.DIRT);
        if (task.dirt >= task.requiredDirt) {
            task.stage = task.resumeStage == null ? Stage.LEVEL_SITE : task.resumeStage;
            task.resumeStage = null;
            task.currentResource = null;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            return;
        }
        if (world.getTime() < task.nextResourceSearch) return;
        ResourceTarget target = task.currentResource;
        int dirtSearchRadius = expandedSearchRadius(Math.max(16, VillageExpansionConfig.woodSearchRadius / 2),
                task.resourceSearchFailures);
        if (target == null || !world.getBlockState(target.block()).isIn(BlockTags.DIRT)) {
            if (!acquireSpatialSearchPermit(world)) return;
            target = findDirtTarget(world, task.site, villager, dirtSearchRadius);
            task.currentResource = target;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
        }
        if (target == null) {
            task.resourceSearchFailures++;
            task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
            int attemptsPerExpansion = Math.max(1, VillageExpansionConfig.noResourceSearchLimit);
            if (task.resourceSearchFailures % attemptsPerExpansion == 0) {
                VillageExpansionMod.LOGGER.warn("Builder {} still needs terrain fill material near {}; expanding the dirt search radius to {} blocks",
                        uuidShort(villager.getUuid()), task.site,
                        expandedSearchRadius(Math.max(16, VillageExpansionConfig.woodSearchRadius / 2),
                                task.resourceSearchFailures));
            }
            // Some biomes have little exposed dirt. After widening the search,
            // obtain one stone-material block through the cave/stair-mine system
            // and use it as an acceptable fill substitute instead of skipping
            // the leveling operation or idling forever on this stage.
            if (task.resourceSearchFailures >= attemptsPerExpansion * 2) {
                if (task.temporaryStoneRequirement < 0) {
                    task.temporaryStoneRequirement = task.requiredStone;
                }
                task.requiredStone = 1;
                task.resumeStage = Stage.LEVEL_SITE;
                task.stoneSearchFailures = 0;
                task.resourceSearchFailures = 0;
                task.stage = Stage.GATHER_STONE;
                task.currentMine = null;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
            }
            return;
        }
        if (!moveNear(villager, task, target.stand(), VillageExpansionConfig.navigationSpeed)) return;
        equipBestTool(villager, ToolKind.SHOVEL);
        if (tickBreakBlock(world, villager, task, target.block(), ToolKind.SHOVEL, ItemTags.DIRT)) {
            task.dirt = countInventoryItems(villager, ItemTags.DIRT);
            task.currentResource = null;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            task.resourceSearchFailures = 0;
        }
    }

    private static ResourceTarget findDirtTarget(ServerWorld world, BlockPos around, VillagerEntity villager) {
        return findDirtTarget(world, around, villager,
                expandedSearchRadius(Math.max(16, VillageExpansionConfig.woodSearchRadius / 2), 0));
    }

    private static ResourceTarget findDirtTarget(ServerWorld world, BlockPos around, VillagerEntity villager,
                                                 int radius) {
        ResourceTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        // Bounded random sampling: at most 48 columns, instead of scanning the full square.
        // Repeated searches expand the radius, so sparse biomes remain discoverable over time.
        int samples = 48;
        int diameter = radius * 2 + 1;
        for (int i = 0; i < samples; i++) {
            int x = around.getX() + world.random.nextInt(diameter) - radius;
            int z = around.getZ() + world.random.nextInt(diameter) - radius;
            int surfaceY = findSurfaceYFast(world, x, z, 16);
            if (surfaceY == Integer.MIN_VALUE) continue;
            BlockPos block = new BlockPos(x, surfaceY, z);
            if (!isLoaded(world, block)) continue;
            BlockState state = world.getBlockState(block);
            if (!state.isIn(BlockTags.DIRT) || state.hasBlockEntity()) continue;
            BlockPos stand = findStandingPosition(world, block, villager);
            if (stand == null) continue;
            double distance = block.getSquaredDistance(villager.getBlockPos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new ResourceTarget(block.toImmutable(), stand);
            }
        }
        return best;
    }

    private static void tickGatherWood(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.wood >= task.requiredWood) {
            villager.getNavigation().stop();
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            task.stoneSearchFailures = 0;
            task.resourceSearchFailures = 0;
            task.stage = Stage.GATHER_STONE;
            return;
        }

        if (task.currentResource == null) {
            if (world.getTime() < task.nextResourceSearch) {
                return;
            }
            if (!acquireSpatialSearchPermit(world)) return;
            ResourceTarget target = findWoodTarget(world, task.site, villager,
                    expandedSearchRadius(VillageExpansionConfig.woodSearchRadius, task.resourceSearchFailures));
            if (target == null) {
                task.resourceSearchFailures++;
                task.nextResourceSearch = world.getTime() + 200L;
                if (task.resourceSearchFailures % Math.max(1, VillageExpansionConfig.noResourceSearchLimit) == 0) {
                    VillageExpansionMod.LOGGER.warn("Builder {} has not found enough natural tree logs near {}; expanding the wood search radius to {} blocks and keeping the project active",
                            uuidShort(villager.getUuid()), task.site,
                            expandedSearchRadius(VillageExpansionConfig.woodSearchRadius, task.resourceSearchFailures));
                }
                return;
            }
            task.currentResource = target;
            task.resourceSearchFailures = 0;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
        }

        ResourceTarget target = task.currentResource;
        if (!world.getBlockState(target.block).isIn(BlockTags.LOGS)
                && !(task.currentTreeBase != null && task.currentTreeBase.equals(target.block()))) {
            task.currentResource = null;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            return;
        }
        if (!moveNear(villager, task, target.stand, VillageExpansionConfig.navigationSpeed)) return;
        if (tickCutTree(world, villager, task, target.block)) {
            task.wood = countInventoryItems(villager, ItemTags.LOGS);
            task.currentResource = null;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            task.actionTimer = 4;
        }
    }

    private static boolean isWoodTargetReserved(ServerWorld world, BlockPos candidate, UUID requester) {
        RegistryKey<World> dimension = world.getRegistryKey();
        for (Map.Entry<UUID, BuilderTask> entry : ACTIVE_TASKS.entrySet()) {
            if (entry.getKey().equals(requester)) continue;
            BuilderTask active = entry.getValue();
            if (active.dimension.equals(dimension) && active.currentResource != null
                    && active.currentResource.block().equals(candidate)) return true;
        }
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        return saved.getActiveTaskResourceBlocks(requester).stream().anyMatch(candidate::equals);
    }

    private static ResourceTarget findWoodTarget(ServerWorld world, BlockPos around, VillagerEntity villager) {
        return findWoodTarget(world, around, villager, (Item) null);
    }

    private static ResourceTarget findWoodTarget(ServerWorld world, BlockPos around, VillagerEntity villager, Item requiredLog) {
        return findWoodTarget(world, around, villager, requiredLog,
                expandedSearchRadius(VillageExpansionConfig.woodSearchRadius, 0));
    }

    private static ResourceTarget findWoodTarget(ServerWorld world, BlockPos around, VillagerEntity villager,
                                                 int radius) {
        return findWoodTarget(world, around, villager, null, radius);
    }

    private static ResourceTarget findWoodTarget(ServerWorld world, BlockPos around, VillagerEntity villager,
                                                 Item requiredLog, int radius) {
        ResourceTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        // Natural-tree detection is relatively expensive. Cap root candidates and let
        // later, radius-expanded searches discover sparse forests without one huge scan.
        int samples = 24;
        int diameter = radius * 2 + 1;
        for (int i = 0; i < samples; i++) {
            int x = around.getX() + world.random.nextInt(diameter) - radius;
            int z = around.getZ() + world.random.nextInt(diameter) - radius;
            int groundY = findSurfaceYFast(world, x, z, 24);
            if (groundY == Integer.MIN_VALUE) continue;
            BlockPos possibleRoot = new BlockPos(x, groundY + 1, z);
            if (!isLoaded(world, possibleRoot)) continue;
            BlockPos root = findNaturalTreeRoot(world, possibleRoot);
            if (root == null) continue; // Includes protected-building validation.
            if (requiredLog != null && !world.getBlockState(root).getBlock().asItem().equals(requiredLog)) continue;
            if (isWoodTargetReserved(world, root, villager.getUuid())) continue;
            BlockPos stand = findStandingPosition(world, root, villager);
            if (stand == null) continue;
            double distance = root.getSquaredDistance(villager.getBlockPos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new ResourceTarget(root.toImmutable(), stand);
            }
        }
        return best;
    }

    private static void tickGatherStone(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        boolean exactStoneRequest = task.resumeStage == Stage.GATHER_MATERIAL && task.pendingMaterialItem != null;
        if (!exactStoneRequest && !hasToolAtLeast(villager, ToolKind.PICKAXE, 0)) {
            task.pendingMaterialItem = Items.WOODEN_PICKAXE;
            task.pendingMaterialCount = 1;
            task.materialReturnStage = Stage.GATHER_STONE;
            task.resumeStage = null;
            task.stage = Stage.GATHER_MATERIAL;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            return;
        }
        task.stone = exactStoneRequest
                ? countSpecificItem(villager, task.pendingMaterialItem)
                : countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
        if (task.stone >= task.requiredStone) {
            restoreTemporaryStoneRequirement(task);
            task.stoneSearchFailures = 0;
            villager.getNavigation().stop();
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            task.stage = task.resumeStage == null ? Stage.BUILD : task.resumeStage;
            task.resumeStage = null;
            return;
        }

        if (task.currentMine == null) {
            if (world.getTime() < task.nextResourceSearch) return;
            equipBestTool(villager, ToolKind.PICKAXE);
            BlockPos searchFrom = task.quarryCenter != null ? task.quarryCenter
                    : (task.mineFace == null ? task.site : task.mineFace);
            int caveRadius = task.quarryCenter != null ? 5 : expandedStoneSearchRadius(task.stoneSearchFailures);
            if (!acquireSpatialSearchPermit(world)) return;
            MiningTarget exposed = exactStoneRequest
                    ? findExposedCaveStone(world, searchFrom, villager, task.pendingMaterialItem, caveRadius)
                    : findExposedCaveStone(world, searchFrom, villager, caveRadius);
            if (exposed != null) {
                task.currentMine = exposed;
                if (task.quarryCenter == null) task.quarryCenter = exposed.rock().toImmutable();
                // A successful scan breaks the streak of unsuccessful searches.
                task.stoneSearchFailures = 0;
                task.resourceSearchFailures = 0;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
            } else {
                if (task.quarryCenter != null) {
                    task.quarryCenter = null;
                    task.nextResourceSearch = world.getTime() + 1L;
                    return;
                }
                if (exactStoneRequest && !isMineableMaterialRequest(task.pendingMaterialItem)) {
                    task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
                    return;
                }
                task.resourceSearchFailures++;
                task.stoneSearchFailures++;
                task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
                task.moveTarget = null;
                task.requestedMoveTarget = null;

                int mineAfterFailures = Math.max(1, VillageExpansionConfig.stoneSearchFailuresBeforeMine);
                if (task.stoneSearchFailures >= mineAfterFailures) {
                    task.stage = Stage.MINE_TUNNEL;
                    VillageExpansionMod.LOGGER.warn(
                            "Builder {} could not find the requested stone material after {} searches; starting a stair mine at least {} blocks from village centre {}",
                            uuidShort(villager.getUuid()), task.stoneSearchFailures,
                            VillageExpansionConfig.mineMinimumDistanceFromVillage,
                            villageCenterOrSite(getVillageRecord(world, task.villageId), task.site));
                } else {
                    VillageExpansionMod.LOGGER.info(
                            "Builder {} could not find the requested stone material (search {}/{}); widening the cave search before starting a mine",
                            uuidShort(villager.getUuid()), task.stoneSearchFailures, mineAfterFailures);
                }
                return;
            }
        }

        MiningTarget mine = task.currentMine;
        if (!moveNear(villager, task, mine.stand(), VillageExpansionConfig.navigationSpeed)) return;
        BlockState rock = world.getBlockState(mine.rock());
        if (!isMineableStoneOrOre(rock, villager)) {
            task.currentMine = null;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            villager.getNavigation().stop();
            task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
            return;
        }
        equipBestTool(villager, ToolKind.PICKAXE);
        if (!tickBreakBlock(world, villager, task, mine.rock(), ToolKind.PICKAXE,
                exactStoneRequest ? null : ItemTags.STONE_TOOL_MATERIALS,
                exactStoneRequest ? task.pendingMaterialItem : null)) return;
        task.stone = exactStoneRequest
                ? countSpecificItem(villager, task.pendingMaterialItem)
                : countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
        task.currentMine = null;
        task.moveTarget = null;
        task.requestedMoveTarget = null;
        task.resourceSearchFailures = 0;
        task.stoneSearchFailures = 0;
        task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
    }

    /** Searches exposed stone/ore along safe, dry cave floor cells before digging. */
    private static MiningTarget findExposedCaveStone(ServerWorld world, BlockPos around, VillagerEntity villager) {
        return findExposedCaveStone(world, around, villager, null,
                VillageExpansionConfig.caveSearchRadius);
    }

    private static MiningTarget findExposedCaveStone(ServerWorld world, BlockPos around, VillagerEntity villager, Item wantedDrop) {
        return findExposedCaveStone(world, around, villager, wantedDrop,
                VillageExpansionConfig.caveSearchRadius);
    }

    private static MiningTarget findExposedCaveStone(ServerWorld world, BlockPos around, VillagerEntity villager,
                                                     int radius) {
        return findExposedCaveStone(world, around, villager, null, radius);
    }

    private static MiningTarget findExposedCaveStone(ServerWorld world, BlockPos around, VillagerEntity villager,
                                                     Item wantedDrop, int radius) {
        // Broad cave discovery is a fallback, not a reason to scan a huge 3D
        // volume synchronously. Only a small grid is sampled; if no source is found,
        // the worker continues its structured mine and checks newly exposed faces.
        int effectiveRadius = Math.min(radius, 6);
        int verticalDepth = Math.min(VillageExpansionConfig.caveSearchDepth,
                wantedDrop == Items.COAL ? 12 : 8);
        int minY = Math.max(world.getBottomY() + 2, around.getY() - verticalDepth);
        int upwardRange = wantedDrop == Items.COAL ? 6 : 2;
        int maxY = Math.min(world.getTopY() - 3, around.getY() + upwardRange);
        int sampleStep = 6;
        MiningTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = around.getX() - effectiveRadius; x <= around.getX() + effectiveRadius; x += sampleStep) {
            for (int z = around.getZ() - effectiveRadius; z <= around.getZ() + effectiveRadius; z += sampleStep) {
                if (!world.isChunkLoaded(new ChunkPos(x >> 4, z >> 4).toLong())) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos stand = new BlockPos(x, y, z);
                    if (!world.getBlockState(stand).isAir() || !world.getBlockState(stand.up()).isAir()
                            || !world.getFluidState(stand).isEmpty() || !world.getFluidState(stand.up()).isEmpty()
                            || !world.getBlockState(stand.down()).isSolidBlock(world, stand.down())) continue;
                    // General stone collection still prefers a cave. An exact
                    // material request may also use a safe, exposed rock face on
                    // a mountain/ravine; requiring a roof here made visible stone
                    // outcrops invisible to the worker.
                    if (wantedDrop == null && !isCaveLikeAirCell(world, stand)) continue;
                    for (Direction direction : Direction.Type.HORIZONTAL) {
                        BlockPos rockPos = stand.offset(direction);
                        if (!isLoaded(world, rockPos)) continue;
                        BlockState rockState = world.getBlockState(rockPos);
                        if (wantedDrop == null ? !rockState.isIn(BlockTags.BASE_STONE_OVERWORLD)
                                : (!isMineableStoneOrOre(rockState, villager) || !isWantedMaterialBlock(rockState, wantedDrop))) continue;
                        if (isStoneTargetReserved(world, rockPos, villager.getUuid())) continue;
                        double distance = rockPos.getSquaredDistance(villager.getBlockPos());
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = new MiningTarget(rockPos.toImmutable(), stand.toImmutable(), List.of());
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Filters open-air cliffs and surface ledges: a candidate must have a rock ceiling and at least one side wall. */
    private static boolean isCaveLikeAirCell(ServerWorld world, BlockPos stand) {
        boolean hasCeiling = false;
        // Four fixed probes are enough to distinguish covered cave cells from open cliffs.
        for (int dy : CAVE_ROOF_PROBES) {
            BlockPos above = stand.up(dy);
            if (!isLoaded(world, above)) return false;
            BlockState state = world.getBlockState(above);
            if (!state.isAir() && state.isSolidBlock(world, above)) {
                hasCeiling = true;
                break;
            }
        }
        if (!hasCeiling) return false;
        int walls = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos side = stand.offset(direction);
            if (isLoaded(world, side) && world.getBlockState(side).isSolidBlock(world, side)) walls++;
        }
        return walls >= 1;
    }

    private static boolean isMineableStoneOrOre(BlockState state, VillagerEntity villager) {
        if (state.isIn(BlockTags.BASE_STONE_OVERWORLD)) return true;
        Item minedBlockItem = state.getBlock().asItem();
        if (minedBlockItem != Items.AIR && new ItemStack(minedBlockItem).isIn(ItemTags.STONE_TOOL_MATERIALS)) {
            return villager.getEquippedStack(EquipmentSlot.MAINHAND).isSuitableFor(state);
        }
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        String path = id.getPath();
        boolean ore = path.endsWith("_ore") || path.endsWith("_ore_deepslate")
                || path.equals("ancient_debris") || path.equals("raw_iron_block")
                || path.equals("raw_copper_block") || path.equals("raw_gold_block");
        if (!ore) return false;
        // Avoid spending time on ore that the currently available pickaxe cannot harvest.
        return villager.getEquippedStack(EquipmentSlot.MAINHAND).isSuitableFor(state);
    }

    private static void tickMineTunnel(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        boolean exactStoneRequest = task.resumeStage == Stage.GATHER_MATERIAL && task.pendingMaterialItem != null;
        task.stone = exactStoneRequest
                ? countSpecificItem(villager, task.pendingMaterialItem)
                : countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
        if (task.stone >= task.requiredStone) {
            restoreTemporaryStoneRequirement(task);
            task.stoneSearchFailures = 0;
            task.stage = task.resumeStage == null ? Stage.BUILD : task.resumeStage;
            task.resumeStage = null;
            villager.getNavigation().stop();
            return;
        }
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        VillageExpansionState.VillageRecord village = saved.getVillage(task.villageId);
        if (task.mineEntrance == null || task.mineFace == null || task.mineDirection == null) {
            BlockPos villageCenter = village == null ? task.site : village.center();
            int minimumMineDistance = VillageExpansionConfig.mineMinimumDistanceFromVillage;
            long minimumMineDistanceSquared = (long) minimumMineDistance * minimumMineDistance;
            BlockPos entrance = village == null ? null : village.mines().stream()
                    .filter(pos -> horizontalDistanceSquared(pos, villageCenter) >= minimumMineDistanceSquared)
                    .filter(pos -> !isMineReserved(world, pos, villager.getUuid()))
                    .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(villager.getBlockPos())))
                    .orElse(null);
            if (entrance == null) {
                if (!acquireSpatialSearchPermit(world)) return;
                entrance = findMineEntrance(world, task.site, villageCenter,
                        village == null ? List.of() : village.mines(),
                        expandedSearchRadius(Math.max(36, VillageExpansionConfig.caveSearchRadius),
                                task.resourceSearchFailures), villager.getUuid());
            }
            if (entrance == null) {
                task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
                task.stage = Stage.GATHER_STONE;
                return;
            }
            task.mineEntrance = entrance;
            task.mineFace = entrance.up();
            task.mineDirection = mineDirectionAwayFrom(villageCenter, entrance);
            task.mineFace = findExistingMineFront(world, entrance.up(), task.mineDirection);
            task.mineSteps = countMineSteps(entrance.up(), task.mineFace, task.mineDirection);
            if (village != null) saved.registerMine(village.id(), entrance);
        }

        if (task.mineSteps >= VillageExpansionConfig.mineMaxLength) {
            // Continue the same remembered gallery in another segment. The
            // total tunnel can exceed mineMaxLength without resetting its path.
            task.mineSteps = 0;
        }

        // The builder first walks to the current tunnel face, then clears one block at a time.
        if (!moveNear(villager, task, task.mineFace, VillageExpansionConfig.navigationSpeed)) return;
        // A one-block descent per stair step produces a continuous 1x2 mining tunnel.
        BlockPos nextStand = task.mineFace.offset(task.mineDirection).down();
        BlockPos feet = nextStand;
        BlockPos head = nextStand.up();
        if (!isLoaded(world, feet) || !isLoaded(world, head)) return;
        equipBestTool(villager, ToolKind.PICKAXE);
        if (feet.getY() <= world.getBottomY() + 2
                || world.getBlockState(feet).getHardness(world, feet) < 0.0F
                || (!world.getBlockState(head).isAir()
                    && world.getBlockState(head).getHardness(world, head) < 0.0F)) {
            if (startAlternateMine(world, villager, task, saved, village, villageCenterOrSite(village, task.site))) {
                return;
            }
            task.stage = Stage.GATHER_STONE;
            task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
            task.mineEntrance = null;
            task.mineFace = null;
            task.mineDirection = null;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            return;
        }
        if (!world.getBlockState(feet).isAir()) {
            if (!tickBreakBlock(world, villager, task, feet, ToolKind.PICKAXE,
                    exactStoneRequest ? null : ItemTags.STONE_TOOL_MATERIALS,
                    exactStoneRequest ? task.pendingMaterialItem : null)) return;
            return;
        }
        if (!world.getBlockState(head).isAir()) {
            if (!tickBreakBlock(world, villager, task, head, ToolKind.PICKAXE,
                    exactStoneRequest ? null : ItemTags.STONE_TOOL_MATERIALS,
                    exactStoneRequest ? task.pendingMaterialItem : null)) return;
            return;
        }

        BlockPos floor = nextStand.down();
        if (!world.getBlockState(floor).isSolidBlock(world, floor)) {
            if (consumeOneTaggedItem(villager, ItemTags.STONE_TOOL_MATERIALS)) {
                world.setBlockState(floor, Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
            } else {
                task.stage = Stage.GATHER_STONE;
                task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
                return;
            }
        }

        if (!moveNear(villager, task, nextStand, VillageExpansionConfig.navigationSpeed)) return;
        task.mineFace = nextStand.toImmutable();
        task.mineSteps++;
        task.moveTarget = null;
        task.requestedMoveTarget = null;
        if (task.mineSteps % VillageExpansionConfig.mineSupportInterval == 0) {
            addMineSupport(world, villager, task.mineFace, task.mineDirection);
        }

        // Exact ore requests inspect the newly exposed tunnel walls immediately.
        // The previous broad cave scan ran only every third step and could miss a
        // narrow coal vein behind the builder's current mining face.
        boolean exactRequest = task.resumeStage == Stage.GATHER_MATERIAL && task.pendingMaterialItem != null;
        if (exactRequest) {
            MiningTarget vein = findRequestedMaterialAlongMine(world, task.mineFace,
                    villager, task.pendingMaterialItem, 2);
            if (vein != null) {
                task.currentMine = vein;
                task.stage = Stage.GATHER_STONE;
                task.nextResourceSearch = world.getTime() + 1L;
                return;
            }
        }

        // A breakthrough is detected by the cave scan, not by filling the tunnel back in.
        if (task.mineSteps % 3 == 0 && world.getTime() >= task.nextResourceSearch) {
            int caveRadius = expandedSearchRadius(VillageExpansionConfig.caveSearchRadius,
                    task.resourceSearchFailures);
            MiningTarget cave = exactRequest
                    ? findExposedCaveStone(world, task.mineFace, villager, task.pendingMaterialItem, caveRadius)
                    : findExposedCaveStone(world, task.mineFace, villager, caveRadius);
            task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
            if (cave != null) {
                task.currentMine = cave;
                task.stage = Stage.GATHER_STONE;
            }
        }
        if (task.stone >= task.requiredStone) {
            restoreTemporaryStoneRequirement(task);
            task.stage = task.resumeStage == null ? Stage.BUILD : task.resumeStage;
            task.resumeStage = null;
        }
    }

    private static void restoreTemporaryStoneRequirement(BuilderTask task) {
        if (task.temporaryStoneRequirement >= 0) {
            task.requiredStone = task.temporaryStoneRequirement;
            task.temporaryStoneRequirement = -1;
        }
    }

    private static int countMineSteps(BlockPos start, BlockPos face, Direction direction) {
        int horizontal = direction.getAxis() == Direction.Axis.X
                ? Math.abs(face.getX() - start.getX()) : Math.abs(face.getZ() - start.getZ());
        return Math.max(horizontal, Math.abs(face.getY() - start.getY()));
    }

    /**
     * Looks for an exact requested ore/material in the freshly exposed mine walls.
     * Candidate work positions must be dry, two blocks high and have a solid floor,
     * so the builder can mine the vein without entering an unsafe cavity.
     */
    private static MiningTarget findRequestedMaterialAlongMine(ServerWorld world, BlockPos mineFace,
                                                                VillagerEntity villager, Item wanted,
                                                                int radius) {
        if (wanted == null) return null;
        MiningTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos orePos = mineFace.add(dx, dy, dz);
                    if (!isLoaded(world, orePos) || isStoneTargetReserved(world, orePos, villager.getUuid())) continue;
                    BlockState oreState = world.getBlockState(orePos);
                    if (!isWantedMaterialBlock(oreState, wanted)
                            || !isMineableStoneOrOre(oreState, villager)) continue;

                    for (Direction direction : Direction.values()) {
                        BlockPos stand = orePos.offset(direction);
                        BlockPos head = stand.up();
                        BlockPos floor = stand.down();
                        if (!isLoaded(world, stand) || !isLoaded(world, head) || !isLoaded(world, floor)
                                || !world.getBlockState(stand).isAir()
                                || !world.getBlockState(head).isAir()
                                || !world.getFluidState(stand).isEmpty()
                                || !world.getFluidState(head).isEmpty()
                                || !world.getBlockState(floor).isSolidBlock(world, floor)
                                || hasLivingEntityInBlock(world, stand, villager)
                                || hasLivingEntityInBlock(world, head, villager)) continue;

                        double distance = stand.getSquaredDistance(villager.getBlockPos());
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = new MiningTarget(orePos.toImmutable(), stand.toImmutable(), List.of());
                        }
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos findExistingMineFront(ServerWorld world, BlockPos start, Direction direction) {
        BlockPos face = start.toImmutable();
        for (int i = 0; i < VillageExpansionConfig.mineMaxLength; i++) {
            BlockPos next = face.offset(direction).down();
            if (!isLoaded(world, next) || !world.getBlockState(next).isAir()
                    || !world.getBlockState(next.up()).isAir()
                    || !world.getBlockState(next.down()).isSolidBlock(world, next.down())) break;
            face = next;
        }
        return face;
    }

    private static BlockPos findMineEntrance(ServerWorld world, BlockPos site, BlockPos villageCenter,
                                             List<BlockPos> knownMines, int maxRadius, UUID requester) {
        int minimumDistance = VillageExpansionConfig.mineMinimumDistanceFromVillage;
        int searchLimit = Math.max(minimumDistance, maxRadius);
        long minimumDistanceSquared = (long) minimumDistance * minimumDistance;
        // Keep the spiral sparse: 8 directions per ring, each surface check bounded.
        for (int radius = minimumDistance; radius <= searchLimit; radius += 12) {
            for (int i = 0; i < 8; i++) {
                double angle = i * (Math.PI * 2.0D / 8.0D);
                int x = villageCenter.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = villageCenter.getZ() + (int) Math.round(Math.sin(angle) * radius);
                int y = findSurfaceYFast(world, x, z, 16);
                if (y == Integer.MIN_VALUE) continue;
                BlockPos ground = new BlockPos(x, y, z);
                if (horizontalDistanceSquared(ground, villageCenter) < minimumDistanceSquared) continue;
                BlockPos feet = ground.up();
                BlockPos head = feet.up();
                if (!isLoaded(world, ground) || !isLoaded(world, head)
                        || !isTerrain(world.getBlockState(ground))) continue;
                if (!world.getBlockState(feet).isAir() || !world.getBlockState(head).isAir()
                        || !world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty()) continue;
                if (hasLivingEntityInBlock(world, feet, null) || hasLivingEntityInBlock(world, head, null)) continue;
                if (Math.abs(y - site.getY()) > 6) continue;
                boolean tooCloseToKnownMine = false;
                for (BlockPos mine : knownMines) {
                    if (mine.getSquaredDistance(ground) < 16.0D * 16.0D) {
                        tooCloseToKnownMine = true;
                        break;
                    }
                }
                if (tooCloseToKnownMine) continue;
                if (requester != null && isMineReserved(world, ground, requester)) continue;
                return ground.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos villageCenterOrSite(VillageExpansionState.VillageRecord village, BlockPos site) {
        return village == null ? site : village.center();
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static VillageExpansionState.VillageRecord getVillageRecord(ServerWorld world, long villageId) {
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        return saved.getVillage(villageId);
    }

    private static boolean isMineReserved(ServerWorld world, BlockPos entrance, UUID requester) {
        for (Map.Entry<UUID, BuilderTask> entry : ACTIVE_TASKS.entrySet()) {
            if (entry.getKey().equals(requester)) continue;
            BuilderTask active = entry.getValue();
            if (active.dimension.equals(world.getRegistryKey()) && active.mineEntrance != null
                    && active.mineEntrance.equals(entrance)) return true;
        }
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        return saved.getActiveTaskMineEntrances(requester).stream().anyMatch(entrance::equals);
    }

    private static boolean startAlternateMine(ServerWorld world, VillagerEntity villager, BuilderTask task,
                                              VillageExpansionState saved,
                                              VillageExpansionState.VillageRecord village,
                                              BlockPos villageCenter) {
        List<BlockPos> known = village == null ? List.of() : village.mines();
        if (!acquireSpatialSearchPermit(world)) return true; // Defer; keep the current tunnel state intact.
        BlockPos entrance = findMineEntrance(world, task.site, villageCenter, known,
                expandedSearchRadius(Math.max(36, VillageExpansionConfig.caveSearchRadius),
                        task.resourceSearchFailures), villager.getUuid());
        if (entrance == null) return false;
        task.mineEntrance = entrance;
        task.mineDirection = mineDirectionAwayFrom(villageCenter, entrance);
        task.mineFace = findExistingMineFront(world, entrance.up(), task.mineDirection);
        task.mineSteps = countMineSteps(entrance.up(), task.mineFace, task.mineDirection);
        task.moveTarget = null;
        task.requestedMoveTarget = null;
        task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
        if (village != null) saved.registerMine(village.id(), entrance);
        VillageExpansionMod.LOGGER.info("Villager {} opened a secondary mine at {}", uuidShort(villager.getUuid()), entrance);
        return true;
    }

    private static Direction mineDirectionAwayFrom(BlockPos center, BlockPos entrance) {
        int dx = entrance.getX() - center.getX();
        int dz = entrance.getZ() - center.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void addMineSupport(ServerWorld world, VillagerEntity villager,
                                       BlockPos stand, Direction tunnelDirection) {
        Direction side = tunnelDirection.rotateYClockwise();
        List<BlockPos> supportPositions = new ArrayList<>();
        for (Direction offset : new Direction[]{side, side.getOpposite()}) {
            BlockPos support = stand.up().offset(offset);
            if (isLoaded(world, support) && (world.getBlockState(support).isAir()
                    || world.getBlockState(support).isReplaceable())) supportPositions.add(support);
        }
        BlockPos beam = stand.up(2);
        if (isLoaded(world, beam) && (world.getBlockState(beam).isAir()
                || world.getBlockState(beam).isReplaceable())) supportPositions.add(beam);
        if (supportPositions.isEmpty() || countInventoryItems(villager, ItemTags.LOGS) < supportPositions.size()) return;
        for (BlockPos support : supportPositions) {
            if (consumeOneTaggedItem(villager, ItemTags.LOGS)) {
                world.setBlockState(support, Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    private static boolean isStoneTargetReserved(ServerWorld world, BlockPos candidate, UUID requester) {
        RegistryKey<World> dimension = world.getRegistryKey();
        for (Map.Entry<UUID, BuilderTask> entry : ACTIVE_TASKS.entrySet()) {
            if (entry.getKey().equals(requester)) continue;
            BuilderTask active = entry.getValue();
            if (active.dimension.equals(dimension) && active.currentMine != null
                    && active.currentMine.rock().equals(candidate)) return true;
        }
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        return saved.getActiveTaskMiningBlocks(requester).stream().anyMatch(candidate::equals);
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
            if (dropped.isEmpty() || (acceptedTag != null && !dropped.isIn(acceptedTag))) {
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

    private static int collectSpecificDrops(ServerWorld world, VillagerEntity villager, BlockPos origin, Item wanted) {
        int collected = 0;
        Box area = new Box(origin.getX() - 3.0D, origin.getY() - 3.0D, origin.getZ() - 3.0D,
                origin.getX() + 4.0D, origin.getY() + 4.0D, origin.getZ() + 4.0D);
        for (ItemEntity entity : world.getEntitiesByClass(ItemEntity.class, area,
                item -> !item.isRemoved() && item.getStack().isOf(wanted))) {
            ItemStack stack = entity.getStack();
            int before = stack.getCount();
            ItemStack remainder = villager.getInventory().addStack(stack.copy());
            collected += before - remainder.getCount();
            if (remainder.isEmpty()) entity.discard(); else entity.setStack(remainder);
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

    /**
     * Returns the item a builder must actually collect to place a planned block.
     * Grass blocks are represented by dirt in the material plan because vanilla
     * grass_block drops dirt unless the tool has Silk Touch.
     */
    private static Item requiredItemForBlock(BlockState state) {
        // Flowers and ground vegetation can appear in some structure palettes as
        // decorative/environmental blocks. They are not house materials and must
        // never create an unsatisfiable gather request.
        if (isPlant(state) || state.isIn(BlockTags.LEAVES)) return Items.AIR;
        if (state.isOf(Blocks.GRASS_BLOCK)) return Items.DIRT;
        return state.getBlock().asItem();
    }

    /**
     * Detects a saved material request whose item represents optional vegetation.
     * The registry lookup is safe for non-block items and keeps this migration
     * generic for flowers and other Plant blocks, not only short_grass.
     */
    private static boolean isNonStructuralDecorationItem(Item item) {
        if (item == null || item == Items.AIR) return false;
        Identifier itemId = Registries.ITEM.getId(item);
        return Registries.BLOCK.getOrEmpty(itemId)
                .map(block -> {
                    BlockState state = block.getDefaultState();
                    // Flowers are optional in a blueprint, but they are also real
                    // recipe ingredients (for example dandelion -> yellow dye).
                    // Never erase a saved flower request during task migration.
                    return state.isIn(BlockTags.LEAVES)
                            || (!state.isIn(BlockTags.FLOWERS) && isPlant(state));
                })
                .orElse(false);
    }

    /** Expand stone/cobblestone scans after every miss, up to 96 blocks. */
    private static int expandedStoneSearchRadius(int failedStoneSearches) {
        int base = Math.max(8, VillageExpansionConfig.caveSearchRadius);
        int expansionStep = 8;
        return Math.min(96, base + Math.max(0, failedStoneSearches) * expansionStep);
    }

    /** Expand other surface-resource searches after repeated misses, up to 96 blocks. */
    private static int expandedSearchRadius(int configuredRadius, int failedSearches) {
        int base = Math.max(8, configuredRadius);
        int attemptsPerExpansion = Math.max(1, VillageExpansionConfig.noResourceSearchLimit);
        int expansionLevel = Math.min(8, Math.max(0, failedSearches) / attemptsPerExpansion);
        return Math.min(96, base * (1 + expansionLevel));
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

    private static Map<Item, Integer> countBlueprintItems(List<BlockPlacement> blueprint) {
        Map<Item, Integer> result = new HashMap<>();
        for (BlockPlacement placement : blueprint) {
            BlockState state = placement.state();
            if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.HALF)
                    && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) continue;
            if (state.getBlock() instanceof net.minecraft.block.BedBlock && state.contains(BedBlock.PART)
                    && state.get(BedBlock.PART) == BedPart.HEAD) continue;
            Item item = requiredItemForBlock(state);
            if (item != Items.AIR) result.merge(item, 1, Integer::sum);
        }
        return result;
    }

    /** Builds the remaining material quota in 32-placement slices, then answers in O(1). */
    private static int countRemainingBlueprintItem(ServerWorld world, BuilderTask task, Item wanted) {
        if (!task.remainingBlueprintCountsReady) {
            if (task.remainingBlueprintCounts == null) {
                task.remainingBlueprintCounts = new HashMap<>();
                task.remainingBlueprintCountsCursor = task.buildIndex;
            }
            int budget = 32;
            while (budget-- > 0 && task.remainingBlueprintCountsCursor < task.blueprint.size()) {
                BlockPlacement placement = task.blueprint.get(task.remainingBlueprintCountsCursor++);
                BlockState existing = world.getBlockState(placement.pos());
                if (!existing.isAir() && !existing.isReplaceable()) continue;
                BlockState planned = placement.state();
                if (isPlant(planned) || planned.isIn(BlockTags.LEAVES)
                        || !blueprintPlacementConsumesItem(planned)) continue;
                Item item = requiredItemForBlock(planned);
                if (item != Items.AIR) task.remainingBlueprintCounts.merge(item, 1, Integer::sum);
            }
            if (task.remainingBlueprintCountsCursor >= task.blueprint.size()) {
                task.remainingBlueprintCountsReady = true;
            } else {
                return -1; // Yield and resume quota indexing next scheduled task slice.
            }
        }
        return task.remainingBlueprintCounts.getOrDefault(wanted, 0);
    }

    private static boolean blueprintPlacementConsumesItem(BlockState state) {
        if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.HALF)
                && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) return false;
        return !(state.getBlock() instanceof net.minecraft.block.BedBlock
                && state.contains(BedBlock.PART) && state.get(BedBlock.PART) == BedPart.HEAD);
    }

    private static void decrementRemainingBlueprintItem(BuilderTask task, BlockState planned) {
        if (task.remainingBlueprintCounts == null || !blueprintPlacementConsumesItem(planned)) return;
        Item item = requiredItemForBlock(planned);
        if (item == Items.AIR) return;
        int count = task.remainingBlueprintCounts.getOrDefault(item, 0);
        if (count <= 1) task.remainingBlueprintCounts.remove(item);
        else task.remainingBlueprintCounts.put(item, count - 1);
    }

    /**
     * Computes only the literal log/stone items present in the blueprint.
     * Recipe dependencies are resolved lazily by ensureItemCountAvailable when
     * the corresponding block is actually built. Running recursive recipe-cost
     * searches here for every blueprint item caused exponential work during
     * builder assignment, especially in modpacks with large ingredient tags.
     */
    private static int remainingTaggedMaterialRequirement(ServerWorld world, VillagerEntity villager,
                                                           Map<Item, Integer> plan, TagKey<Item> tag) {
        if (villager == null || plan == null || tag == null) return 0;
        int literalRequirement = 0;
        for (Map.Entry<Item, Integer> entry : plan.entrySet()) {
            if (entry.getKey() != null && new ItemStack(entry.getKey()).isIn(tag)) {
                literalRequirement += Math.max(0, entry.getValue());
            }
        }
        return Math.max(0, literalRequirement - countInventoryItems(villager, tag));
    }

    /** Uses registered crafting/smelting recipes and consumes only the exact ingredients. */
    private static boolean ensureItemAvailable(ServerWorld world, VillagerEntity villager, BuilderTask task,
                                               Item desired, int depth, Set<Item> visiting) {
        return ensureItemCountAvailable(world, villager, task, desired, 1, depth, visiting);
    }

    private static void deferMaterialGoal(BuilderTask task, Item item, int count) {
        if (item == null || item == Items.AIR) return;
        MaterialGoal last = task.materialGoalStack.isEmpty() ? null
                : task.materialGoalStack.get(task.materialGoalStack.size() - 1);
        if (last != null && last.item() == item) {
            task.materialGoalStack.set(task.materialGoalStack.size() - 1,
                    new MaterialGoal(item, Math.max(last.count(), count)));
        } else {
            // Recursive calls unwind from the nearest missing leaf to the root;
            // append parents in that order so the leaf's immediate parent resumes first.
            task.materialGoalStack.add(new MaterialGoal(item, Math.max(1, count)));
        }
    }

    private static boolean resumeDeferredMaterialGoal(BuilderTask task) {
        if (!task.materialGoalStack.isEmpty()) {
            MaterialGoal next = task.materialGoalStack.remove(0);
            task.pendingMaterialItem = next.item();
            task.pendingMaterialCount = next.count();
            task.stage = Stage.GATHER_MATERIAL;
            return true;
        }
        if (task.deferredMaterialItem != null) {
            task.pendingMaterialItem = task.deferredMaterialItem;
            task.pendingMaterialCount = Math.max(1, task.deferredMaterialCount);
            task.deferredMaterialItem = null;
            task.deferredMaterialCount = 0;
            task.stage = Stage.GATHER_MATERIAL;
            return true;
        }
        return false;
    }

    private static boolean ensureItemCountAvailable(ServerWorld world, VillagerEntity villager, BuilderTask task,
                                                    Item desired, int amount, int depth, Set<Item> visiting) {
        // Pick up a previous recipe output that could not fit in the villager inventory.
        collectSpecificDrops(world, villager, villager.getBlockPos(), desired);
        int alreadyHave = countSpecificItem(villager, desired);
        if (isToolItem(desired) && MaterialAcquisitionPlanner.hasToolOrBetter(villager, desired)) {
            alreadyHave = Math.max(1, alreadyHave);
        }
        if (alreadyHave >= amount) {
            task.lazyMaterialPlans.remove(desired);
            return true;
        }
        int missing = amount - alreadyHave;

        if (depth >= 12 || !visiting.add(desired)) {
            task.pendingMaterialItem = desired;
            task.pendingMaterialCount = amount;
            return false;
        }

        try {
            MaterialPlanningJob plan = task.lazyMaterialPlans.get(desired);
            if (plan != null && plan.amount != amount) {
                task.lazyMaterialPlans.remove(desired);
                plan = null;
            }
            if (plan != null && plan.complete && !plan.viable
                    && world.getTime() >= task.nextMaterialSearch) {
                task.lazyMaterialPlans.remove(desired);
                plan = null;
            }

            if (plan == null) {
                // Supplier scanning is also scheduled lazily; it must not run
                // synchronously for every villager on the same server tick.
                plan = new MaterialPlanningJob(desired, amount, world.getTime());
                if (task.planBlockedItem == desired) task.planBlockedItem = null;
                task.lazyMaterialPlans.put(desired, plan);
                task.pendingMaterialItem = desired;
                task.pendingMaterialCount = amount;
                task.nextMaterialSearch = plan.nextStepAt;
                return false;
            }

            if (!plan.complete) {
                task.pendingMaterialItem = desired;
                task.pendingMaterialCount = amount;
                task.nextMaterialSearch = plan.nextStepAt;
                return false;
            }
            if (!plan.viable) {
                task.pendingMaterialItem = desired;
                task.pendingMaterialCount = amount;
                task.nextMaterialSearch = world.getTime() + 200L;
                return false;
            }

            // The route and its selected recipe were resolved across several
            // controller ticks. Reuse that decision; do not synchronously run the
            // recursive planner again during execution.
            MaterialAcquisitionPlanner.CraftingChoice crafting = plan.crafting;
            MaterialAcquisitionPlanner.StonecuttingChoice stonecutting = plan.stonecutting;
            MaterialAcquisitionPlanner.SmeltingChoice smelting = plan.smelting;
            if (plan.route != MaterialPlanningRoute.DIRECT
                    && VillageExpansionConfig.useRecipeBookForCrafting) {
                if (plan.route == MaterialPlanningRoute.CRAFTING && crafting != null) {
                    if (MaterialAcquisitionPlanner.requiresCraftingTable(crafting.entry().value(), desired)
                            && countSpecificItem(villager, Items.CRAFTING_TABLE) == 0
                            && !ensureItemCountAvailable(world, villager, task, Items.CRAFTING_TABLE,
                            1, depth + 1, visiting)) {
                        deferMaterialGoal(task, desired, amount);
                        return false;
                    }
                    for (Map.Entry<Item, Integer> ingredient : crafting.ingredients().entrySet()) {
                        if (!ensureItemCountAvailable(world, villager, task, ingredient.getKey(),
                                ingredient.getValue(), depth + 1, visiting)) {
                            deferMaterialGoal(task, desired, amount);
                            return false;
                        }
                    }
                    for (Map.Entry<Item, Integer> ingredient : crafting.ingredients().entrySet()) {
                        if (!consumeItems(villager, ingredient.getKey(), ingredient.getValue())) {
                            task.pendingMaterialItem = ingredient.getKey();
                            task.pendingMaterialCount = ingredient.getValue();
                            deferMaterialGoal(task, desired, amount);
                            return false;
                        }
                    }
                    ItemStack output = crafting.entry().value().getResult(world.getRegistryManager()).copy();
                    int batches = (missing + output.getCount() - 1) / output.getCount();
                    output.setCount(output.getCount() * batches);
                    ItemStack remainder = villager.getInventory().addStack(output);
                    if (!remainder.isEmpty()) {
                        world.spawnEntity(new ItemEntity(world, villager.getX(), villager.getY() + 0.4D,
                                villager.getZ(), remainder));
                    }
                    return countSpecificItem(villager, desired) >= amount;
                }

                // Stonecutting is another registered recipe path for blocks such as
                // walls, stairs and slabs. The stonecutter item is itself a normal
                // prerequisite, so its iron/stone/plank dependencies are planned too.
                if (plan.route == MaterialPlanningRoute.STONECUTTING && stonecutting != null) {
                    if (countSpecificItem(villager, Items.STONECUTTER) == 0
                            && !ensureItemCountAvailable(world, villager, task, Items.STONECUTTER,
                            1, depth + 1, visiting)) {
                        deferMaterialGoal(task, desired, amount);
                        return false;
                    }
                    ItemStack cutOutput = stonecutting.entry().value().getResult(world.getRegistryManager()).copy();
                    int missingNow = Math.max(0, amount - countSpecificItem(villager, desired));
                    int cutBatches = Math.max(1, (missingNow + cutOutput.getCount() - 1) / cutOutput.getCount());
                    if (!ensureItemCountAvailable(world, villager, task, stonecutting.input(),
                            cutBatches, depth + 1, visiting)) {
                        deferMaterialGoal(task, desired, amount);
                        return false;
                    }
                    if (!consumeItems(villager, stonecutting.input(), cutBatches)) {
                        task.pendingMaterialItem = stonecutting.input();
                        task.pendingMaterialCount = cutBatches;
                        deferMaterialGoal(task, desired, amount);
                        return false;
                    }
                    // The registered stonecutting recipe is simulated just like crafting:
                    // all required inputs are present before any are consumed.
                    cutOutput.setCount(cutOutput.getCount() * cutBatches);
                    ItemStack cutRemainder = villager.getInventory().addStack(cutOutput);
                    if (!cutRemainder.isEmpty()) {
                        world.spawnEntity(new ItemEntity(world, villager.getX(), villager.getY() + 0.4D,
                                villager.getZ(), cutRemainder));
                    }
                    return countSpecificItem(villager, desired) >= amount;
                }

                // Smelting is also planned through the dependency evaluator. One
                // cook operation is run at a time; after completion the parent goal
                // is reevaluated and another batch is started only if still needed.
                if (plan.route == MaterialPlanningRoute.SMELTING && smelting != null) {
                    int cookTicks = Math.max(1, smelting.entry().value().getCookingTime());
                    // A furnace is a real dependency in the plan. The processing
                    // animation remains simulated, but the worker must first craft
                    // and retain the workstation item from its normal recipe chain.
                    if (countSpecificItem(villager, Items.FURNACE) == 0
                            && !ensureItemCountAvailable(world, villager, task, Items.FURNACE,
                            1, depth + 1, visiting)) {
                        deferMaterialGoal(task, desired, amount);
                        return false;
                    }
                    if (!ensureItemCountAvailable(world, villager, task, smelting.input(), 1,
                            depth + 1, visiting)) {
                        deferMaterialGoal(task, desired, amount);
                        return false;
                    }
                    if (task.smeltFuelTicksRemaining < cookTicks
                            && !consumeSmeltingFuel(villager, task, cookTicks)) {
                        task.pendingMaterialItem = Items.COAL;
                        task.pendingMaterialCount = 1;
                        deferMaterialGoal(task, desired, amount);
                        return false;
                    }
                    if (!consumeItems(villager, smelting.input(), 1)) {
                        task.pendingMaterialItem = smelting.input();
                        task.pendingMaterialCount = 1;
                        deferMaterialGoal(task, desired, amount);
                        return false;
                    }
                    task.smeltFuelTicksRemaining -= cookTicks;
                    ItemStack result = smelting.entry().value().getResult(world.getRegistryManager());
                    task.pendingSmeltOutput = desired;
                    task.smeltOutputCount = result.getCount();
                    task.smeltTicksRemaining = cookTicks;
                    task.pendingMaterialItem = desired;
                    task.pendingMaterialCount = amount;
                    task.resumeStage = task.stage == Stage.GATHER_MATERIAL ? Stage.GATHER_MATERIAL : task.stage;
                    task.stage = Stage.SMELT_MATERIAL;
                    task.moveTarget = null;
                    task.requestedMoveTarget = null;
                    world.playSound(null, villager.getBlockPos(), SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE,
                            SoundCategory.BLOCKS, 0.35F, 1.0F);
                    return false;
                }

            }
            task.pendingMaterialItem = desired;
            task.pendingMaterialCount = amount;
            return false;
        } finally {
            // Failed alternatives must not poison sibling branches with a stale
            // cycle marker. This is essential when recipes have interchangeable
            // ingredients or multiple ways to craft the same item.
            visiting.remove(desired);
        }
    }

    private static void advanceOneLazyPlanningStep(ServerWorld world) {
        long now = world.getTime();
        RegistryKey<World> dimension = world.getRegistryKey();
        if (dimension.equals(lastLazyPlanningDimension) && lastLazyPlanningTick == now) return;

        int total = ACTIVE_TASK_ORDER.size();
        for (int checked = 0; checked < total; checked++) {
            if (lazyPlanningCursor >= ACTIVE_TASK_ORDER.size()) lazyPlanningCursor = 0;
            if (ACTIVE_TASK_ORDER.isEmpty()) return;
            UUID selected = ACTIVE_TASK_ORDER.get(lazyPlanningCursor++);
            BuilderTask task = ACTIVE_TASKS.get(selected);
            if (task == null || !task.dimension.equals(dimension) || task.pendingMaterialItem == null) continue;
            MaterialPlanningJob job = task.lazyMaterialPlans.get(task.pendingMaterialItem);
            if (job == null || job.complete || now < job.nextStepAt) continue;
            Entity entity = world.getEntity(selected);
            if (!(entity instanceof VillagerEntity villager) || !villager.isAlive()) continue;

            lastLazyPlanningDimension = dimension;
            lastLazyPlanningOwner = selected;
            lastLazyPlanningTick = now;

            // One supplier lookup is a complete planning slice. If it finds no
            // provider, recipe indexing/choice starts on a later tick.
            if (!job.providerChecked) {
                job.providerChecked = true;
                UUID excludedProvider = task.rejectedProfessionalItem == job.item
                        && now < task.rejectedProfessionalUntil ? task.rejectedProfessionalProviderUuid : null;
                if (!acquireSpatialSearchPermit(world)) {
                    job.providerChecked = false;
                    return;
                }
                VillagerEntity provider = ProfessionalResourceExchange.findNearestProvider(
                        world, villager, job.item, excludedProvider);
                if (provider != null) {
                    task.professionalProviderUuid = provider.getUuid();
                    task.professionalVisitStartedAt = Math.max(1L, now);
                    task.stage = Stage.GATHER_FROM_PROFESSIONAL;
                    task.moveTarget = provider.getBlockPos().toImmutable();
                    task.requestedMoveTarget = null;
                    task.nextMaterialSearch = now;
                    task.lazyMaterialPlans.remove(job.item);
                    return;
                }
                job.nextStepAt = now + VillageExpansionConfig.lazyPlanningStepIntervalTicks;
                task.nextMaterialSearch = job.nextStepAt;
                return;
            }

            if (VillageExpansionConfig.useRecipeBookForCrafting
                    && !MaterialAcquisitionPlanner.isRecipeIndexReady(world)) {
                MaterialAcquisitionPlanner.advanceRecipeIndex(world,
                        VillageExpansionConfig.lazyPlanningRecipeEntriesPerTick);
                job.nextStepAt = now + 1L;
                task.nextMaterialSearch = job.nextStepAt;
                return;
            }

            if (job.phase == 0 && !acquireSpatialSearchPermit(world)) return;
            job.advance(world, villager);
            job.nextStepAt = job.complete ? now : now + VillageExpansionConfig.lazyPlanningStepIntervalTicks;
            if (job.complete && !job.viable) {
                task.nextMaterialSearch = now + 200L;
                if (task.planBlockedItem != job.item) {
                    task.planBlockedItem = job.item;
                    VillageExpansionMod.LOGGER.warn(
                            "Lazy planner found no supported acquisition route for {} x{} (villager {}); retrying later",
                            Registries.ITEM.getId(job.item), job.amount, uuidShort(selected));
                }
            } else {
                task.nextMaterialSearch = job.nextStepAt;
            }
            if (job.complete) {
                if (job.viable && task.planBlockedItem == job.item) task.planBlockedItem = null;
                VillageExpansionMod.LOGGER.debug("Lazy plan for villager {} resolved {} x{} via {}",
                        uuidShort(selected), Registries.ITEM.getId(job.item), job.amount, job.route);
            }
            return;
        }
    }

    private enum MaterialPlanningRoute { DIRECT, CRAFTING, STONECUTTING, SMELTING, NONE }

    /** One resumable decision. Each call to advance() performs exactly one bounded planner phase. */
    private static final class MaterialPlanningJob {
        private final Item item;
        private final int amount;
        private long nextStepAt;
        private int phase;
        private boolean providerChecked;
        private double directScore = Double.POSITIVE_INFINITY;
        private MaterialAcquisitionPlanner.CraftingChoice crafting;
        private MaterialAcquisitionPlanner.StonecuttingChoice stonecutting;
        private MaterialAcquisitionPlanner.SmeltingChoice smelting;
        private MaterialPlanningRoute route = MaterialPlanningRoute.NONE;
        private boolean complete;
        private boolean viable;

        private MaterialPlanningJob(Item item, int amount, long now) {
            this.item = item;
            this.amount = amount;
            this.nextStepAt = now;
        }

        private void advance(ServerWorld world, VillagerEntity villager) {
            if (complete) return;
            switch (phase++) {
                case 0 -> directScore = MaterialAcquisitionPlanner.estimateDirectSourceCostForPlanning(
                        world, villager, item, amount);
                case 1 -> {
                    if (VillageExpansionConfig.useRecipeBookForCrafting) {
                        crafting = MaterialAcquisitionPlanner.chooseBestCraftingRecipe(world, villager, item, amount);
                    }
                }
                case 2 -> {
                    if (VillageExpansionConfig.useRecipeBookForCrafting) {
                        stonecutting = MaterialAcquisitionPlanner.chooseBestStonecuttingRecipe(world, villager, item, amount);
                    }
                }
                case 3 -> {
                    if (VillageExpansionConfig.useRecipeBookForCrafting) {
                        smelting = MaterialAcquisitionPlanner.chooseBestSmeltingRecipe(world, villager, item, amount);
                    }
                    chooseRoute();
                }
                default -> chooseRoute();
            }
            if (phase >= 4 || !VillageExpansionConfig.useRecipeBookForCrafting && phase >= 1) {
                chooseRoute();
                complete = true;
            }
        }

        private String phaseLabel() {
            if (!providerChecked) return "fornecedores";
            return switch (phase) {
                case 0 -> "fonte direta";
                case 1 -> "crafting";
                case 2 -> "stonecutter";
                case 3 -> "fundição";
                default -> "finalizando";
            };
        }

        private void chooseRoute() {
            double craftingScore = crafting == null ? Double.POSITIVE_INFINITY : crafting.score();
            double cuttingScore = stonecutting == null ? Double.POSITIVE_INFINITY : stonecutting.score();
            double smeltingScore = smelting == null ? Double.POSITIVE_INFINITY : smelting.score();
            double recipeScore = Math.min(craftingScore, Math.min(cuttingScore, smeltingScore));
            if (directScore <= recipeScore && directScore < 1.0e11D) {
                route = MaterialPlanningRoute.DIRECT;
            } else if (craftingScore <= cuttingScore && craftingScore <= smeltingScore && crafting != null) {
                route = MaterialPlanningRoute.CRAFTING;
            } else if (cuttingScore <= smeltingScore && stonecutting != null) {
                route = MaterialPlanningRoute.STONECUTTING;
            } else if (smelting != null) {
                route = MaterialPlanningRoute.SMELTING;
            } else if (directScore < 1.0e11D) {
                route = MaterialPlanningRoute.DIRECT;
            } else {
                route = MaterialPlanningRoute.NONE;
            }
            viable = route != MaterialPlanningRoute.NONE;
        }
    }

    private static boolean isToolItem(Item item) {
        return item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE || item == Items.IRON_PICKAXE
                || item == Items.GOLDEN_PICKAXE || item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE
                || item == Items.WOODEN_AXE || item == Items.STONE_AXE || item == Items.IRON_AXE
                || item == Items.GOLDEN_AXE || item == Items.DIAMOND_AXE || item == Items.NETHERITE_AXE
                || item == Items.WOODEN_SHOVEL || item == Items.STONE_SHOVEL || item == Items.IRON_SHOVEL
                || item == Items.GOLDEN_SHOVEL || item == Items.DIAMOND_SHOVEL || item == Items.NETHERITE_SHOVEL
                || item == Items.SHEARS;
    }

    private static boolean consumeSmeltingFuel(VillagerEntity villager, BuilderTask task, int requiredTicks) {
        while (task.smeltFuelTicksRemaining < requiredTicks) {
            boolean consumedFuel = false;
            for (int slot = 0; slot < villager.getInventory().size(); slot++) {
                ItemStack stack = villager.getInventory().getStack(slot);
                if (stack.isEmpty()) continue;
                // Preserve building planks/logs and sticks for recipes (torches, doors, etc.).
                // Processing uses mined coal or charcoal only, so the material plan stays intact.
                int burnTicks = stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL) ? 1600 : 0;
                if (burnTicks <= 0) continue;
                stack.decrement(1);
                villager.getInventory().markDirty();
                task.smeltFuelTicksRemaining += burnTicks;
                consumedFuel = true;
                break;
            }
            if (!consumedFuel) return false;
        }
        return true;
    }

    private static void tickSmeltMaterial(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.pendingSmeltOutput == null || task.smeltTicksRemaining <= 0) {
            task.pendingSmeltOutput = null;
            task.smeltTicksRemaining = 0;
            task.stage = task.resumeStage == null ? Stage.BUILD : task.resumeStage;
            task.resumeStage = null;
            return;
        }
        task.smeltTicksRemaining = Math.max(0, task.smeltTicksRemaining - task.elapsedControllerTicks);
        if (world.getTime() % 10L == 0L) {
            world.spawnParticles(ParticleTypes.SMOKE, villager.getX(), villager.getY() + 0.4D,
                    villager.getZ(), 1, 0.12D, 0.08D, 0.12D, 0.005D);
            world.spawnParticles(ParticleTypes.FLAME, villager.getX(), villager.getY() + 0.25D,
                    villager.getZ(), 1, 0.08D, 0.02D, 0.08D, 0.0D);
        }
        if (task.smeltTicksRemaining > 0) return;
        ItemStack result = new ItemStack(task.pendingSmeltOutput, Math.max(1, task.smeltOutputCount));
        ItemStack remainder = villager.getInventory().addStack(result);
        if (!remainder.isEmpty()) world.spawnEntity(new ItemEntity(world, villager.getX(), villager.getY() + 0.4D,
                villager.getZ(), remainder));
        task.pendingSmeltOutput = null;
        task.smeltOutputCount = 1;
        task.stage = task.resumeStage == null ? Stage.BUILD : task.resumeStage;
        task.resumeStage = null;
        task.nextMaterialSearch = world.getTime() + 1L;
    }

    private static Item requiredPickaxeForDrop(Item wanted) {
        Item required = MaterialAcquisitionPlanner.requiredToolFor(wanted);
        return required == Items.WOODEN_PICKAXE || required == Items.STONE_PICKAXE
                || required == Items.IRON_PICKAXE ? required : null;
    }

    private static boolean hasToolAtLeast(VillagerEntity villager, ToolKind kind, int tier) {
        if (toolTier(villager.getEquippedStack(EquipmentSlot.MAINHAND).getItem(), kind) >= tier) return true;
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            if (toolTier(villager.getInventory().getStack(slot).getItem(), kind) >= tier) return true;
        }
        return false;
    }

    private static Item strippedLogItemFor(Item wanted) {
        Identifier id = Registries.ITEM.getId(wanted);
        String path = id.getPath();
        if (!path.startsWith("stripped_") || !(path.endsWith("_log") || path.endsWith("_wood")
                || path.endsWith("_stem") || path.endsWith("_hyphae"))) return null;
        Identifier rawId = Identifier.of(id.getNamespace(), path.substring("stripped_".length()));
        Item raw = Registries.ITEM.get(rawId);
        return raw == Items.AIR ? null : raw;
    }

    private static ResourceTarget findRawLogForStripping(ServerWorld world, BlockPos around,
                                                          VillagerEntity villager, Item rawLog, int radius) {
        ResourceTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        int samples = 40;
        int diameter = radius * 2 + 1;
        for (int i = 0; i < samples; i++) {
            int x = around.getX() + world.random.nextInt(diameter) - radius;
            int z = around.getZ() + world.random.nextInt(diameter) - radius;
            int surface = findSurfaceYFast(world, x, z, 24);
            if (surface == Integer.MIN_VALUE) continue;
            BlockPos possibleRoot = new BlockPos(x, surface + 1, z);
            BlockPos root = findNaturalTreeRoot(world, possibleRoot);
            if (root == null || !world.getBlockState(root).getBlock().asItem().equals(rawLog)) continue;
            BlockPos stand = findStandingPosition(world, root, villager);
            if (stand == null) continue;
            double distance = root.getSquaredDistance(villager.getBlockPos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new ResourceTarget(root.toImmutable(), stand);
            }
        }
        return best;
    }

    private static void tickGatherFromProfessional(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        Item wanted = task.pendingMaterialItem;
        if (wanted == null || task.pendingMaterialCount <= 0) {
            clearProfessionalVisit(task);
            task.stage = Stage.GATHER_MATERIAL;
            return;
        }

        VillagerEntity provider = null;
        double supplierRadius = VillageExpansionConfig.professionalResourceSearchRadius;
        if (task.professionalProviderUuid != null
                && world.getEntity(task.professionalProviderUuid) instanceof VillagerEntity savedProvider
                && savedProvider.isAlive()
                && villager.squaredDistanceTo(savedProvider) <= supplierRadius * supplierRadius
                && ProfessionalResourceExchange.hasAvailableOffer(savedProvider, wanted)) {
            provider = savedProvider;
        }
        if (provider == null) {
            if (task.professionalProviderUuid != null) rejectProfessionalProvider(task, wanted,
                    task.professionalProviderUuid, world.getTime() + 600L);
            UUID excluded = task.rejectedProfessionalItem == wanted
                    && world.getTime() < task.rejectedProfessionalUntil
                    ? task.rejectedProfessionalProviderUuid : null;
            if (!acquireSpatialSearchPermit(world)) return;
            provider = ProfessionalResourceExchange.findNearestProvider(world, villager, wanted, excluded);
            if (provider == null) {
                VillageExpansionMod.LOGGER.info("Builder {} could not reach an available professional source for {}; returning to normal acquisition planning",
                        uuidShort(villager.getUuid()), Registries.ITEM.getId(wanted));
                clearProfessionalVisit(task);
                task.stage = Stage.GATHER_MATERIAL;
                task.nextMaterialSearch = world.getTime() + 20L;
                return;
            }
            task.professionalProviderUuid = provider.getUuid();
            task.professionalVisitStartedAt = Math.max(1L, world.getTime());
        }

        // Do not orbit a blocked workstation forever. After a bounded visit
        // window the ordinary recipe/natural-source planner gets another chance.
        long visitLimit = Math.max(400L, (long) VillageExpansionConfig.navigationStallTimeoutTicks
                * Math.max(2, VillageExpansionConfig.navigationMaxRepaths) * 3L);
        if (task.professionalVisitStartedAt > 0L
                && world.getTime() - task.professionalVisitStartedAt > visitLimit) {
            VillageExpansionMod.LOGGER.warn("Builder {} could not reach professional {} for {}; falling back to self-acquisition",
                    uuidShort(villager.getUuid()), uuidShort(provider.getUuid()), Registries.ITEM.getId(wanted));
            rejectProfessionalProvider(task, wanted, provider.getUuid(), world.getTime() + 600L);
            clearProfessionalVisit(task);
            task.stage = Stage.GATHER_MATERIAL;
            task.nextMaterialSearch = world.getTime() + 20L;
            return;
        }

        if (!moveNear(villager, task, provider.getBlockPos(), VillageExpansionConfig.navigationSpeed)) return;

        int received = ProfessionalResourceExchange.transferFreeSupplies(world, villager, provider,
                wanted, task.pendingMaterialCount);
        if (received > 0) {
            VillageExpansionMod.LOGGER.info("Builder {} received {} x{} for free from {} ({})",
                    uuidShort(villager.getUuid()), Registries.ITEM.getId(wanted), received,
                    uuidShort(provider.getUuid()), Registries.VILLAGER_PROFESSION.getId(provider.getVillagerData().getProfession()));
            task.resourceSearchFailures = 0;
            if (task.rejectedProfessionalProviderUuid != null
                    && task.rejectedProfessionalProviderUuid.equals(provider.getUuid())) {
                task.rejectedProfessionalProviderUuid = null;
                task.rejectedProfessionalItem = null;
                task.rejectedProfessionalUntil = 0L;
            }
        } else {
            VillageExpansionMod.LOGGER.debug("Professional {} no longer has usable stock or inventory space for {} (builder {})",
                    uuidShort(provider.getUuid()), Registries.ITEM.getId(wanted), uuidShort(villager.getUuid()));
            rejectProfessionalProvider(task, wanted, provider.getUuid(), world.getTime() + 600L);
        }
        clearProfessionalVisit(task);
        task.stage = Stage.GATHER_MATERIAL;
        task.nextMaterialSearch = world.getTime() + 1L;
    }

    private static void rejectProfessionalProvider(BuilderTask task, Item wanted, UUID provider, long until) {
        task.rejectedProfessionalProviderUuid = provider;
        task.rejectedProfessionalItem = wanted;
        task.rejectedProfessionalUntil = until;
    }

    private static void clearProfessionalVisit(BuilderTask task) {
        task.professionalProviderUuid = null;
        task.professionalVisitStartedAt = 0L;
        task.moveTarget = null;
        task.requestedMoveTarget = null;
        task.lastNavigationDistance = Double.MAX_VALUE;
        task.lastNavigationProgressAt = 0L;
        task.lastNavigationCommandAt = 0L;
        task.navigationRepathCount = 0;
        task.navigationFallbackIndex = 0;
    }

    private static void tickGatherMaterial(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.materialReturnStage == null && task.resumeStage != null
                && task.resumeStage != Stage.GATHER_MATERIAL) {
            task.materialReturnStage = task.resumeStage;
        }
        Item wanted = task.pendingMaterialItem;
        if (wanted == Items.GRASS_BLOCK) {
            // Migrate tasks that were created before grass blocks were mapped to
            // their actual vanilla drop (dirt).
            wanted = Items.DIRT;
            task.pendingMaterialItem = wanted;
        }
        if (wanted == null) {
            task.stage = task.resumeStage == null ? Stage.BUILD : task.resumeStage;
            task.resumeStage = null;
            return;
        }
        int requestedCount = Math.max(1, task.pendingMaterialCount);
        boolean itemAlreadyAvailable = countSpecificItem(villager, wanted) >= requestedCount
                || (isToolItem(wanted) && MaterialAcquisitionPlanner.hasToolOrBetter(villager, wanted));
        MaterialPlanningJob activeLazyPlan = task.lazyMaterialPlans.get(wanted);
        if (!itemAlreadyAvailable && activeLazyPlan != null && !activeLazyPlan.complete) {
            // The global round-robin scheduler advances this plan in bounded slices.
            // Do not invoke recursive cost estimation or source scans from this task tick.
            task.nextMaterialSearch = activeLazyPlan.nextStepAt;
            return;
        }
        // Once a concrete source/work position has been selected, keep executing
        // that target instead of rebuilding the full recipe/provider decision every tick.
        // This is especially important for large modded ingredient tags.
        boolean woolRequest = new ItemStack(wanted).isIn(ItemTags.WOOL);
        boolean activeAcquisitionTarget = task.currentResource != null
                || task.currentTreeBase != null || (woolRequest && task.moveTarget != null);
        boolean shouldEvaluatePlanner = itemAlreadyAvailable
                || (!activeAcquisitionTarget && world.getTime() >= task.nextMaterialSearch);

        Item requestedGoal = wanted;
        if (shouldEvaluatePlanner
                && ensureItemCountAvailable(world, villager, task, wanted, requestedCount, 0, new HashSet<>())) {
            task.resourceSearchFailures = 0;
            if (!resumeDeferredMaterialGoal(task)) {
                task.pendingMaterialItem = null;
                task.pendingMaterialCount = 0;
                task.stage = task.materialReturnStage != null ? task.materialReturnStage
                        : (task.resumeStage == null ? Stage.BUILD : task.resumeStage);
                task.resumeStage = null;
                task.materialReturnStage = null;
                task.planBlockedItem = null;
            }
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            return;
        }
        if (task.stage != Stage.GATHER_MATERIAL) return; // Crafting may have started a timed smelting job.
        if (task.pendingMaterialItem != null && task.pendingMaterialItem != requestedGoal) return;
        if (task.pendingMaterialItem == null) task.pendingMaterialItem = wanted;
        if (shouldEvaluatePlanner) {
            wanted = task.pendingMaterialItem;
            MaterialPlanningJob completedPlan = task.lazyMaterialPlans.get(wanted);
            if (completedPlan == null || !completedPlan.complete) return;
            if (!completedPlan.viable) {
                if (task.planBlockedItem != wanted) {
                    task.planBlockedItem = wanted;
                    VillageExpansionMod.LOGGER.error(
                            "Builder {} has no safe acquisition plan for {}; the goal remains queued for a later retry.",
                            uuidShort(villager.getUuid()), Registries.ITEM.getId(wanted));
                }
                task.nextMaterialSearch = world.getTime() + 200L;
                return;
            }
            task.planBlockedItem = null;
        } else if (!activeAcquisitionTarget && !itemAlreadyAvailable) {
            // The next retry is driven by nextMaterialSearch. Do not execute an
            // expensive recursive planner while waiting for that retry window.
            return;
        }

        Item rawLogForStripping = strippedLogItemFor(wanted);
        if (rawLogForStripping != null) {
            if (!hasToolAtLeast(villager, ToolKind.AXE, 0)
                    && !ensureItemCountAvailable(world, villager, task, Items.WOODEN_AXE, 1, 0, new HashSet<>())) {
                deferMaterialGoal(task, wanted, requestedCount);
                task.resumeStage = Stage.GATHER_MATERIAL;
                return;
            }
            ResourceTarget stripTarget = task.currentResource;
            if (stripTarget == null || !isLoaded(world, stripTarget.block())
                    || !world.getBlockState(stripTarget.block()).getBlock().asItem().equals(rawLogForStripping)) {
                if (!acquireSpatialSearchPermit(world)) return;
                stripTarget = findRawLogForStripping(world, task.site, villager, rawLogForStripping,
                        expandedSearchRadius(VillageExpansionConfig.woodSearchRadius, task.resourceSearchFailures));
                task.currentResource = stripTarget;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
            }
            if (stripTarget != null) {
                if (moveNear(villager, task, stripTarget.stand(), VillageExpansionConfig.navigationSpeed)) {
                    equipBestTool(villager, ToolKind.AXE);
                    BlockState rawState = world.getBlockState(stripTarget.block());
                    Identifier rawBlockId = Registries.BLOCK.getId(rawState.getBlock());
                    Identifier strippedId = Identifier.of(rawBlockId.getNamespace(), "stripped_" + rawBlockId.getPath());
                    Block strippedBlock = Registries.BLOCK.get(strippedId);
                    if (strippedBlock != Blocks.AIR) {
                        world.setBlockState(stripTarget.block(), strippedBlock.getDefaultState(), Block.NOTIFY_ALL);
                        if (tickBreakBlock(world, villager, task, stripTarget.block(), ToolKind.AXE, null, wanted)) {
                            task.currentResource = null;
                            task.resourceSearchFailures = 0;
                        }
                    } else {
                        task.currentResource = null;
                        task.resourceSearchFailures++;
                    }
                }
                return;
            }
        }

        if (new ItemStack(wanted).isIn(ItemTags.LOGS)) {
            ResourceTarget target = task.currentResource;
            if (task.currentTreeBase != null && task.currentTreeLogItem == wanted) {
                if (target == null) {
                    BlockPos stand = findStandingPosition(world, task.currentTreeBase, villager);
                    if (stand != null) target = new ResourceTarget(task.currentTreeBase, stand);
                    task.currentResource = target;
                }
            } else if (target == null || !isNaturalTreeRoot(world, target.block())
                    || !world.getBlockState(target.block()).getBlock().asItem().equals(wanted)) {
                if (!acquireSpatialSearchPermit(world)) return;
                target = findWoodTarget(world, task.site, villager, wanted,
                        expandedSearchRadius(VillageExpansionConfig.woodSearchRadius, task.resourceSearchFailures));
                task.currentResource = target;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
            }
            if (target != null) {
                if (moveNear(villager, task, target.stand(), VillageExpansionConfig.navigationSpeed)
                        && tickCutTree(world, villager, task, target.block())) {
                    task.currentResource = null;
                    task.wood = countInventoryItems(villager, ItemTags.LOGS);
                    task.moveTarget = null;
                    task.requestedMoveTarget = null;
                    task.nextMaterialSearch = world.getTime() + 1L;
                }
                return;
            }
        }

        if (isMineableMaterialRequest(wanted)) {
            // Resolve the tool prerequisite before searching for the ore. This
            // recursively obtains cobblestone, planks and sticks to craft the
            // correct pickaxe instead of repeatedly selecting an unmineable vein.
            Item requiredPickaxe = requiredPickaxeForDrop(wanted);
            if (requiredPickaxe != null && !hasToolAtLeast(villager, ToolKind.PICKAXE,
                    toolTier(requiredPickaxe, ToolKind.PICKAXE))) {
                if (!ensureItemCountAvailable(world, villager, task, requiredPickaxe, 1, 0, new HashSet<>())) {
                    deferMaterialGoal(task, wanted, requestedCount);
                    task.resumeStage = Stage.GATHER_MATERIAL;
                    return;
                }
                equipBestTool(villager, ToolKind.PICKAXE);
            }
            // Stone-like crafting ingredients and raw ores need the mining routine.
            // The shallow generic block scan cannot find buried coal/ores reliably;
            // the mining routine can create a safe stair tunnel and search its walls.
            task.resumeStage = Stage.GATHER_MATERIAL;
            task.requiredStone = requestedCount;
            task.stoneSearchFailures = 0;
            task.resourceSearchFailures = 0;
            task.stage = Stage.GATHER_STONE;
            task.currentMine = null;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            task.nextResourceSearch = world.getTime();
            return;
        }

        if (new ItemStack(wanted).isIn(ItemTags.WOOL)) {
            int sheepRadius = expandedSearchRadius(VillageExpansionConfig.materialSearchRadius,
                    task.resourceSearchFailures);
            Box sheepArea = new Box(task.site.getX() - sheepRadius, task.site.getY() - 12.0D, task.site.getZ() - sheepRadius,
                    task.site.getX() + sheepRadius, task.site.getY() + 12.0D, task.site.getZ() + sheepRadius);
            String woolName = Registries.ITEM.getId(wanted).getPath();
            String colorName = woolName.endsWith("_wool") ? woolName.substring(0, woolName.length() - 5) : "white";
            if (!acquireSpatialSearchPermit(world)) return;
            SheepEntity sheep = world.getEntitiesByClass(SheepEntity.class, sheepArea,
                    candidate -> candidate.isAlive() && candidate.isShearable()
                            && candidate.getColor().getName().equals(colorName)).stream()
                    .min(Comparator.comparingDouble(villager::squaredDistanceTo)).orElse(null);
            if (sheep != null) {
                BlockPos sheepPos = sheep.getBlockPos();
                if (moveNear(villager, task, sheepPos, VillageExpansionConfig.navigationSpeed)) {
                    // Shearing is only available after the builder has crafted
                    // shears through the same recipe/mining pipeline as other tools.
                    if (!ensureItemCountAvailable(world, villager, task, Items.SHEARS,
                            1, 0, new HashSet<>())) {
                        // The recipe system may temporarily replace pendingMaterialItem
                        // with an ingredient such as iron ingots/raw iron/coal. Keep the
                        // wool request separately so it resumes after those ingredients.
                        deferMaterialGoal(task, wanted, requestedCount);
                        if (task.stage == Stage.SMELT_MATERIAL) {
                            task.resumeStage = Stage.GATHER_MATERIAL;
                        }
                        return;
                    }
                    boolean shearsAlreadyInHand = villager.getMainHandStack().isOf(Items.SHEARS);
                    int shearsSlot = -1;
                    if (!shearsAlreadyInHand) {
                        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
                            if (villager.getInventory().getStack(slot).isOf(Items.SHEARS)) {
                                shearsSlot = slot;
                                break;
                            }
                        }
                        if (shearsSlot < 0) return;
                        ItemStack previousMainHand = villager.getMainHandStack().copy();
                        ItemStack heldShears = villager.getInventory().removeStack(shearsSlot);
                        villager.equipStack(EquipmentSlot.MAINHAND, heldShears);
                        sheep.sheared(SoundCategory.BLOCKS);
                        villager.getMainHandStack().damage(1, villager, EquipmentSlot.MAINHAND);
                        ItemStack remainingShears = villager.getMainHandStack();
                        villager.equipStack(EquipmentSlot.MAINHAND, previousMainHand);
                        if (!remainingShears.isEmpty()) villager.getInventory().addStack(remainingShears);
                    } else {
                        sheep.sheared(SoundCategory.BLOCKS);
                        villager.getMainHandStack().damage(1, villager, EquipmentSlot.MAINHAND);
                    }
                    villager.getInventory().markDirty();
                    collectSpecificDrops(world, villager, sheepPos, wanted);
                    task.nextMaterialSearch = world.getTime() + 20L;
                }
                return;
            }
        }

        int searchRadius = expandedSearchRadius(VillageExpansionConfig.materialSearchRadius,
                task.resourceSearchFailures);
        ResourceTarget raw = task.currentResource;
        if (raw == null || !isLoaded(world, raw.block()) || !isLoaded(world, raw.stand())
                || !isWantedMaterialBlock(world.getBlockState(raw.block()), wanted)) {
            task.currentResource = null;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            if (!acquireSpatialSearchPermit(world)) return;
            raw = findDirectMaterialTarget(world, task.site, villager, wanted, searchRadius);
            task.currentResource = raw;
        }
        if (raw != null) {
            if (moveNear(villager, task, raw.stand(), VillageExpansionConfig.navigationSpeed)) {
                BlockState state = world.getBlockState(raw.block());
                ToolKind tool = state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.getHardness(world, raw.block()) > 2.5F
                        ? ToolKind.PICKAXE : ToolKind.SHOVEL;
                equipBestTool(villager, tool);
                if (tickBreakBlock(world, villager, task, raw.block(), tool, null, wanted)) {
                    task.resourceSearchFailures = 0;
                    task.currentResource = null;
                    task.moveTarget = null;
                    task.requestedMoveTarget = null;
                }
                task.nextMaterialSearch = world.getTime() + 1L;
            }
            return;
        }

        task.resourceSearchFailures++;
        task.nextMaterialSearch = world.getTime() + VillageExpansionConfig.materialSearchIntervalTicks;
        if (task.resourceSearchFailures % Math.max(1, VillageExpansionConfig.noResourceSearchLimit) == 0) {
            VillageExpansionMod.LOGGER.warn("Builder {} still needs {} for the blueprint at {}; expanding the search radius to {} blocks and retrying without abandoning the project",
                    uuidShort(villager.getUuid()), Registries.ITEM.getId(wanted), task.site,
                    searchRadius);
            task.currentResource = null;
            task.actionTimer = VillageExpansionConfig.materialSearchIntervalTicks;
        }
    }

    private static ResourceTarget findDirectMaterialTarget(ServerWorld world, BlockPos around,
                                                            VillagerEntity villager, Item wanted, int radius) {
        if (wanted == Items.CLAY_BALL) {
            return findClayTarget(world, around, villager, radius);
        }
        ResourceTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        // A hard cap prevents a 96-block radius from turning into a thousands-of-columns scan.
        // The radius grows on later attempts, while each individual attempt stays cheap.
        int samples = 48;
        int diameter = radius * 2 + 1;
        for (int i = 0; i < samples; i++) {
            int x = around.getX() + world.random.nextInt(diameter) - radius;
            int z = around.getZ() + world.random.nextInt(diameter) - radius;
            int surfaceY = findSurfaceYFast(world, x, z, 16);
            if (surfaceY == Integer.MIN_VALUE) continue;
            int minY = Math.max(world.getBottomY() + 1, surfaceY - 8);
            for (int y = minY; y <= surfaceY + 1; y++) {
                BlockPos block = new BlockPos(x, y, z);
                if (!isLoaded(world, block)) continue;
                BlockState state = world.getBlockState(block);
                if (state.isAir() || state.hasBlockEntity() || state.isIn(BlockTags.LOGS)
                        || state.isIn(BlockTags.LEAVES) || !isWantedMaterialBlock(state, wanted)) continue;
                BlockPos stand = findStandingPosition(world, block, villager);
                if (stand == null) continue;
                double distance = block.getSquaredDistance(villager.getBlockPos());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new ResourceTarget(block.toImmutable(), stand);
                }
            }
        }
        return best;
    }

    /**
     * Clay may be submerged. Sample a bounded number of columns and then find a dry bank
     * from which the villager can reach the clay without pathing into the water.
     */
    private static ResourceTarget findClayTarget(ServerWorld world, BlockPos around,
                                                  VillagerEntity villager, int radius) {
        ResourceTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        int samples = 48;
        int diameter = radius * 2 + 1;
        for (int i = 0; i < samples; i++) {
            int x = around.getX() + world.random.nextInt(diameter) - radius;
            int z = around.getZ() + world.random.nextInt(diameter) - radius;
            int surfaceY = findSurfaceYFast(world, x, z, 16);
            if (surfaceY == Integer.MIN_VALUE) continue;
            int minY = Math.max(world.getBottomY() + 1, surfaceY - 8);
            for (int y = minY; y <= surfaceY + 1; y++) {
                BlockPos clay = new BlockPos(x, y, z);
                if (!isLoaded(world, clay) || !world.getBlockState(clay).isOf(Blocks.CLAY)) continue;
                BlockPos stand = findDryClayWorkStand(world, clay, villager);
                if (stand == null) continue;
                double distance = clay.getSquaredDistance(villager.getBlockPos());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new ResourceTarget(clay.toImmutable(), stand.toImmutable());
                    break; // One reachable clay block is enough for this sampled column.
                }
            }
        }
        return best;
    }

    private static BlockPos findDryClayWorkStand(ServerWorld world, BlockPos clay, VillagerEntity villager) {
        BlockPos best = null;
        double bestVillagerDistance = Double.MAX_VALUE;
        // 24 deterministic shoreline samples (8 directions at radii 2, 4 and 6).
        for (int radius : new int[]{2, 4, 6}) {
            for (int direction = 0; direction < 8; direction++) {
                double angle = direction * (Math.PI * 2.0D / 8.0D);
                int x = clay.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = clay.getZ() + (int) Math.round(Math.sin(angle) * radius);
                int groundY = findSurfaceYFast(world, x, z, 16);
                if (groundY == Integer.MIN_VALUE) continue;
                BlockPos stand = new BlockPos(x, groundY + 1, z);
                BlockPos head = stand.up();
                BlockPos floor = stand.down();
                if (!isLoaded(world, stand) || !isLoaded(world, head)
                        || !world.getBlockState(stand).isAir()
                        || !world.getBlockState(head).isAir()
                        || !world.getFluidState(stand).isEmpty()
                        || !world.getFluidState(head).isEmpty()
                        || !world.getBlockState(floor).isSolidBlock(world, floor)
                        || hasLivingEntityInBlock(world, stand, villager)
                        || hasLivingEntityInBlock(world, head, villager)) continue;
                if (stand.getSquaredDistance(clay) > 64.0D) continue;
                double villagerDistance = stand.getSquaredDistance(villager.getBlockPos());
                if (villagerDistance < bestVillagerDistance) {
                    bestVillagerDistance = villagerDistance;
                    best = stand.toImmutable();
                }
            }
            if (best != null) break;
        }
        return best;
    }

    /**
     * Only raw natural resource blocks may be mined by the generic material gatherer.
     * Do not use block.asItem()==wanted as a fallback: that could make a builder
     * dismantle a door, bed, torch, chest, plank wall or another finished structure
     * when a recipe is unavailable or an item is missing.
     */
    /**
     * Items that must be obtained by mining rather than the shallow surface-resource scan.
     * Keep this limited to resources whose block drops are explicitly supported below.
     */
    private static boolean isMineableMaterialRequest(Item wanted) {
        return MaterialAcquisitionPlanner.sourceFor(wanted) == MaterialAcquisitionPlanner.SourceKind.MINE;
    }

    private static boolean isWantedMaterialBlock(BlockState state, Item wanted) {
        Identifier wantedId = Registries.ITEM.getId(wanted);
        Identifier blockIdentifier = Registries.BLOCK.getId(state.getBlock());
        String wantedPath = wantedId.getPath();
        String blockId = blockIdentifier.getPath();
        // Dye recipes can select different flower items. Match the exact flower
        // block represented by the requested item rather than hard-coding only
        // dandelions or treating every flower as decoration.
        if (state.isIn(BlockTags.FLOWERS) && blockIdentifier.equals(wantedId)) return true;
        if (MaterialAcquisitionPlanner.isCropYieldBlock(state, wanted)) return true;
        return switch (wantedPath) {
            case "sand" -> state.isOf(Blocks.SAND);
            case "red_sand" -> state.isOf(Blocks.RED_SAND);
            case "gravel" -> state.isOf(Blocks.GRAVEL);
            case "dirt" -> state.isOf(Blocks.DIRT) || state.isOf(Blocks.COARSE_DIRT)
                    || state.isOf(Blocks.ROOTED_DIRT) || state.isOf(Blocks.GRASS_BLOCK)
                    || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.MYCELIUM);
            case "cobblestone" -> state.isOf(Blocks.STONE);
            case "cobbled_deepslate" -> state.isOf(Blocks.DEEPSLATE)
                    || blockId.equals("cobbled_deepslate");
            case "coal" -> blockId.equals("coal_ore") || blockId.equals("deepslate_coal_ore");
            case "raw_iron" -> blockId.equals("iron_ore") || blockId.equals("deepslate_iron_ore");
            case "raw_copper" -> blockId.equals("copper_ore") || blockId.equals("deepslate_copper_ore");
            case "raw_gold" -> blockId.equals("gold_ore") || blockId.equals("deepslate_gold_ore");
            case "redstone" -> blockId.equals("redstone_ore") || blockId.equals("deepslate_redstone_ore");
            case "lapis_lazuli" -> blockId.equals("lapis_ore") || blockId.equals("deepslate_lapis_ore");
            case "diamond" -> blockId.equals("diamond_ore") || blockId.equals("deepslate_diamond_ore");
            case "emerald" -> blockId.equals("emerald_ore") || blockId.equals("deepslate_emerald_ore");
            case "clay_ball" -> blockId.equals("clay");
            case "sugar_cane" -> state.isOf(Blocks.SUGAR_CANE);
            case "bamboo" -> state.isOf(Blocks.BAMBOO);
            case "cactus" -> state.isOf(Blocks.CACTUS);
            default -> {
                MaterialAcquisitionPlanner.SourceKind source = MaterialAcquisitionPlanner.sourceFor(wanted);
                boolean naturalBlock = source == MaterialAcquisitionPlanner.SourceKind.NATURAL_BLOCK
                        && state.getBlock().asItem() == wanted
                        && MaterialAcquisitionPlanner.isSafeNaturalBlock(state);
                boolean exactMineableBlock = source == MaterialAcquisitionPlanner.SourceKind.MINE
                        && state.getBlock().asItem() == wanted
                        && !state.isOf(Blocks.STONE) && !state.isOf(Blocks.DEEPSLATE);
                yield naturalBlock || exactMineableBlock;
            }
        };
    }

    private static void tickBuild(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.buildIndex >= task.blueprint.size()) {
            if (!task.pathBlocks.isEmpty() && task.pathIndex < task.pathBlocks.size()) {
                task.stage = Stage.BUILD_PATH;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
                return;
            }
            completeProject(world, villager, task);
            return;
        }
        if (task.actionTimer > 0) return;

        BlockPlacement placement = task.blueprint.get(task.buildIndex);
        // Skip terrain decoration and leaves found in vanilla template palettes.
        // These are not structural materials and most require Silk Touch to collect.
        if (isPlant(placement.state) || placement.state.isIn(BlockTags.LEAVES)) {
            task.constructionStandTarget = null;
            task.cachedConstructionStand = null;
            task.buildIndex++;
            task.actionTimer = 1;
            return;
        }
        BlockState existing = world.getBlockState(placement.pos);
        if (!existing.isAir() && !existing.isReplaceable()) {
            // Do not overwrite blocks placed by players or another task. Keep the
            // cached material quota in sync with the skipped blueprint placement.
            decrementRemainingBlueprintItem(task, placement.state());
            task.constructionStandTarget = null;
            task.cachedConstructionStand = null;
            task.buildIndex++;
            task.actionTimer = 1;
            return;
        }

        // Work from a clear position beside the current block instead of standing
        // in the middle of the footprint. This prevents the builder from placing
        // blocks through its own hitbox or through another villager/mob.
        if (task.constructionStandTarget == null || !task.constructionStandTarget.equals(placement.pos)
                || task.cachedConstructionStand == null) {
            task.constructionStandTarget = placement.pos.toImmutable();
            task.cachedConstructionStand = findConstructionStand(world, placement.pos, villager);
        }
        BlockPos workStand = task.cachedConstructionStand;
        if (workStand == null) {
            task.actionTimer = 10;
            villager.getNavigation().stop();
            return;
        }
        if (!moveNear(villager, task, workStand, VillageExpansionConfig.navigationSpeed)) return;
        if (hasLivingEntityInBlock(world, placement.pos, villager)) {
            // The target block is still occupied. Wait for the resident/mob to move.
            task.actionTimer = 10;
            villager.getNavigation().stop();
            return;
        }

        BlockState state = placement.state;
        boolean consumesBlockItem = true;
        if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.HALF)
                && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) consumesBlockItem = false;
        if (state.getBlock() instanceof net.minecraft.block.BedBlock && state.contains(BedBlock.PART)
                && state.get(BedBlock.PART) == BedPart.HEAD) consumesBlockItem = false;
        Item requiredItem = requiredItemForBlock(state);
        int remainingRequiredCount = consumesBlockItem && requiredItem != Items.AIR
                ? countRemainingBlueprintItem(world, task, requiredItem) : 0;
        if (remainingRequiredCount < 0) {
            task.actionTimer = 1;
            return;
        }
        if (consumesBlockItem && requiredItem != Items.AIR
                && !ensureItemCountAvailable(world, villager, task, requiredItem,
                Math.max(1, remainingRequiredCount), 0, new HashSet<>())) {
            // A recipe may have switched the task to SMELT_MATERIAL. Do not overwrite
            // that stage with GATHER_MATERIAL or the timed smelting job will never finish.
            if (task.stage == Stage.SMELT_MATERIAL) return;
            if (task.pendingMaterialItem == null) {
                task.pendingMaterialItem = requiredItem;
                task.pendingMaterialCount = Math.max(1, remainingRequiredCount);
            }
            task.resumeStage = Stage.BUILD;
            if (task.materialReturnStage == null) task.materialReturnStage = Stage.BUILD;
            task.stage = Stage.GATHER_MATERIAL;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            return;
        }
        if (consumesBlockItem && requiredItem != Items.AIR && !consumeItems(villager, requiredItem, 1)) {
            task.pendingMaterialItem = requiredItem;
            task.pendingMaterialCount = Math.max(1, remainingRequiredCount);
            task.resumeStage = Stage.BUILD;
            task.stage = Stage.GATHER_MATERIAL;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            return;
        }

        if (!world.setBlockState(placement.pos, state, Block.NOTIFY_ALL)) {
            if (consumesBlockItem && requiredItem != Items.AIR) {
                villager.getInventory().addStack(new ItemStack(requiredItem));
                villager.getInventory().markDirty();
            }
            task.actionTimer = 10;
            return;
        }
        decrementRemainingBlueprintItem(task, state);
        task.constructionStandTarget = null;
        task.cachedConstructionStand = null;
        villager.swingHand(Hand.MAIN_HAND);
        task.buildIndex++;
        task.actionTimer = VillageExpansionConfig.buildBlockDelayTicks;
    }

    private static BlockPos findConstructionStand(ServerWorld world, BlockPos target, VillagerEntity builder) {
        Box targetBox = new Box(target);
        int minStandY = Math.max(world.getBottomY() + 1, target.getY() - 12);
        int maxStandY = Math.min(world.getTopY() - 2, target.getY() + 2);
        BlockPos builderPos = builder.getBlockPos();
        for (int radius = 1; radius <= 3; radius++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = target.getX() + dx;
                    int z = target.getZ() + dz;
                    for (int y = minStandY; y <= maxStandY; y++) {
                        BlockPos stand = new BlockPos(x, y, z);
                        BlockPos support = stand.down();
                        if (!isLoaded(world, stand) || !isLoaded(world, stand.up())
                                || !world.getBlockState(stand).isAir()
                                || !world.getBlockState(stand.up()).isAir()
                                || !world.getBlockState(support).isSolidBlock(world, support)
                                || !world.getFluidState(stand).isEmpty()
                                || !world.getFluidState(stand.up()).isEmpty()) continue;
                        Box entityBox = new Box(x + 0.2D, y, z + 0.2D, x + 0.8D, y + 1.95D, z + 0.8D);
                        if (entityBox.intersects(targetBox)) continue;
                        double distance = stand.getSquaredDistance(builderPos);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = stand.toImmutable();
                        }
                    }
                }
            }
            if (best != null) {
                Box entityBox = new Box(best.getX() + 0.2D, best.getY(), best.getZ() + 0.2D,
                        best.getX() + 0.8D, best.getY() + 1.95D, best.getZ() + 0.8D);
                boolean occupied = false;
                for (Entity entity : world.getOtherEntities(builder, entityBox)) {
                    if (entity.isAlive() && entity instanceof net.minecraft.entity.LivingEntity) {
                        occupied = true;
                        break;
                    }
                }
                if (!occupied) return best;
            }
        }
        return null;
    }

    private static boolean hasLivingEntityInBlock(ServerWorld world, BlockPos target, Entity builder) {
        Box targetBox = new Box(target);
        return world.getOtherEntities(builder, targetBox).stream()
                .anyMatch(entity -> entity.isAlive()
                        && entity instanceof net.minecraft.entity.LivingEntity
                        && entity.getBoundingBox().intersects(targetBox));
    }

    private static boolean isWoodMaterialBlock(BlockState state) {
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) return true;
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        return path.startsWith("oak_") || path.startsWith("spruce_") || path.startsWith("birch_")
                || path.startsWith("jungle_") || path.startsWith("acacia_") || path.startsWith("dark_oak_")
                || path.startsWith("mangrove_") || path.startsWith("cherry_") || path.startsWith("bamboo_")
                || path.startsWith("crimson_") || path.startsWith("warped_");
    }

    private static boolean isStoneMaterialBlock(BlockState state) {
        if (state.isIn(BlockTags.BASE_STONE_OVERWORLD)) return true;
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        return path.contains("cobblestone") || path.contains("stone_brick") || path.contains("deepslate")
                || path.contains("sandstone") || path.contains("blackstone") || path.contains("tuff")
                || path.contains("granite") || path.contains("diorite") || path.contains("andesite");
    }

    private static List<BlockPlacement> createBlueprint(BlockPos base, boolean professional,
                                                         VillagerProfession founderProfession) {
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
            add(blocks, base, 3, 2, 2, chooseWorkstation(base, founderProfession));
        }

        // Basic interior lighting helps keep newly built homes usable at night.
        add(blocks, base, 1, 2, 3, Blocks.TORCH.getDefaultState());
        add(blocks, base, 3, 2, 3, Blocks.TORCH.getDefaultState());

        return blocks;
    }

    private static BlockState chooseWorkstation(BlockPos base, VillagerProfession founderProfession) {
        // In a brand-new settlement, make the first job-site house match the
        // founder's existing job whenever that profession has a vanilla POI.
        Identifier professionId = Registries.VILLAGER_PROFESSION.getId(founderProfession);
        if (professionId == null || professionId.getPath().equals("none")) {
            return Blocks.SMITHING_TABLE.getDefaultState();
        }
        if (professionId != null) {
            BlockState matching = switch (professionId.getPath()) {
                case "farmer" -> Blocks.COMPOSTER.getDefaultState();
                case "armorer" -> Blocks.BLAST_FURNACE.getDefaultState();
                case "toolsmith" -> Blocks.SMITHING_TABLE.getDefaultState();
                case "weaponsmith" -> Blocks.GRINDSTONE.getDefaultState();
                case "butcher" -> Blocks.SMOKER.getDefaultState();
                case "fisherman" -> Blocks.BARREL.getDefaultState();
                case "cartographer" -> Blocks.CARTOGRAPHY_TABLE.getDefaultState();
                case "fletcher" -> Blocks.FLETCHING_TABLE.getDefaultState();
                case "librarian" -> Blocks.LECTERN.getDefaultState();
                case "mason" -> Blocks.STONECUTTER.getDefaultState();
                case "leatherworker" -> Blocks.CAULDRON.getDefaultState();
                case "cleric" -> Blocks.BREWING_STAND.getDefaultState();
                case "shepherd" -> Blocks.LOOM.getDefaultState();
                default -> null;
            };
            if (matching != null) {
                return matching;
            }
        }

        // For unemployed/nitwit villagers, rotate through useful vanilla jobs.
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
        return moveNear(villager, task, target, speed, false);
    }

    /**
     * @param abandonIfUnreachable when true, a permanently unreachable work stand
     *                             is reported to the caller so it can choose another
     *                             local position instead of wandering forever.
     */
    private static boolean moveNear(VillagerEntity villager, BuilderTask task, BlockPos target,
                                    double speed, boolean abandonIfUnreachable) {
        long now = villager.getWorld().getTime();
        double distanceSquared = villager.squaredDistanceTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
        if (abandonIfUnreachable && task.failedNavigationTarget != null
                && task.failedNavigationTarget.equals(target)) return false;
        if (task.requestedMoveTarget == null || !task.requestedMoveTarget.equals(target)) {
            task.requestedMoveTarget = target.toImmutable();
            if (task.failedNavigationTarget != null && !task.failedNavigationTarget.equals(target)) {
                task.failedNavigationTarget = null;
            }
            task.moveTarget = target.toImmutable();
            task.lastNavigationDistance = Math.sqrt(distanceSquared);
            task.lastNavigationProgressAt = now;
            task.lastNavigationCommandAt = 0L;
            task.navigationRepathCount = 0;
            task.navigationFallbackIndex = 0;
        } else {
            double distance = Math.sqrt(distanceSquared);
            if (distance + 0.15D < task.lastNavigationDistance) {
                task.lastNavigationDistance = distance;
                task.lastNavigationProgressAt = now;
                task.navigationRepathCount = 0;
                task.navigationFallbackIndex = 0;
                task.moveTarget = target.toImmutable();
            }
        }

        if (distanceSquared <= 4.0D) {
            task.moveTarget = target.toImmutable();
            villager.getNavigation().stop();
            return true;
        }

        boolean stalled = now - task.lastNavigationProgressAt >= VillageExpansionConfig.navigationStallTimeoutTicks;
        if (stalled) {
            if (tryPlaceRescueStep(worldOf(villager), villager, target)) {
                task.lastNavigationProgressAt = now;
                task.lastNavigationDistance = Math.sqrt(distanceSquared);
                task.navigationRepathCount = 0;
                task.moveTarget = target.toImmutable();
                task.lastNavigationCommandAt = 0L;
            }
            task.navigationRepathCount++;
            task.lastNavigationProgressAt = now;
            task.lastNavigationDistance = Math.sqrt(distanceSquared);
            BlockPos alternate = findAlternateStand(worldOf(villager), target, villager, task.navigationFallbackIndex++);
            if (alternate != null && task.navigationRepathCount <= VillageExpansionConfig.navigationMaxRepaths) {
                task.moveTarget = alternate.toImmutable();
            } else if (task.navigationRepathCount >= VillageExpansionConfig.navigationMaxRepaths) {
                task.moveTarget = target.toImmutable();
                task.navigationRepathCount = 0;
                task.navigationFallbackIndex = 0;
                if (abandonIfUnreachable) {
                    task.failedNavigationTarget = target.toImmutable();
                    task.moveTarget = null;
                    villager.getNavigation().stop();
                    VillageExpansionMod.LOGGER.warn("Builder {} could not reach {} during {}; caller will select a nearby fallback",
                            uuidShort(villager.getUuid()), target, task.stage);
                    return false;
                }
                VillageExpansionMod.LOGGER.warn("Builder {} is stuck navigating to {} during {}; resetting its route",
                        uuidShort(villager.getUuid()), target, task.stage);
            }
            task.lastNavigationCommandAt = 0L;
        }

        if (villager.getNavigation().isIdle()
                || now - task.lastNavigationCommandAt >= VillageExpansionConfig.navigationReassertIntervalTicks) {
            BlockPos destination = task.moveTarget == null ? target : task.moveTarget;
            villager.getNavigation().startMovingTo(destination.getX() + 0.5D,
                    destination.getY(), destination.getZ() + 0.5D, speed);
            task.lastNavigationCommandAt = now;
        }
        return false;
    }

    /** Places one temporary step only when a higher target is unreachable from a lower ledge/pit. */
    private static boolean tryPlaceRescueStep(ServerWorld world, VillagerEntity villager, BlockPos target) {
        BlockPos feet = villager.getBlockPos();
        if (target.getY() <= feet.getY() || target.getY() - feet.getY() > 3) return false;
        int dx = Integer.compare(target.getX(), feet.getX());
        int dz = Integer.compare(target.getZ(), feet.getZ());
        if (dx == 0 && dz == 0) return false;
        BlockPos step = feet.add(dx, 0, dz);
        BlockPos head = step.up();
        if (!isLoaded(world, step) || !isLoaded(world, head)
                || !world.getBlockState(step).isReplaceable() || !world.getBlockState(head).isAir()
                || !world.getBlockState(step.down()).isSolidBlock(world, step.down())
                || hasLivingEntityInBlock(world, step, villager) || hasLivingEntityInBlock(world, head, villager)) return false;
        BlockState material = null;
        if (consumeOneTaggedItem(villager, ItemTags.DIRT)) material = Blocks.DIRT.getDefaultState();
        else if (consumeOneTaggedItem(villager, ItemTags.STONE_TOOL_MATERIALS)) material = Blocks.COBBLESTONE.getDefaultState();
        if (material == null) return false;
        world.setBlockState(step, material, Block.NOTIFY_ALL);
        return true;
    }

    private static ServerWorld worldOf(VillagerEntity villager) {
        return (ServerWorld) villager.getWorld();
    }

    private static BlockPos findAlternateStand(ServerWorld world, BlockPos target,
                                               VillagerEntity villager, int attempt) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = target.add(dx, dy, dz);
                        if (!isLoaded(world, candidate) || !world.getBlockState(candidate).isAir()
                                || !world.getBlockState(candidate.up()).isAir()
                                || !world.getBlockState(candidate.down()).isSolidBlock(world, candidate.down())
                                || !world.getFluidState(candidate).isEmpty()) continue;
                        candidates.add(candidate.toImmutable());
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(villager.getBlockPos())));
        return candidates.get(Math.floorMod(attempt, Math.min(candidates.size(), 8)));
    }

    private static void completeProject(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        VillageExpansionState.VillageRecord village = saved.getVillage(task.villageId);

        // A new settlement's meeting point is not free: reserve one gold block
        // for the bell pedestal. The recipe pipeline turns this into a real
        // progression chain (gold ore -> raw gold -> smelting -> ingots -> block).
        boolean needsFoundingCenter = village != null && village.isFounding()
                && task.professional && task.foundingProject && !village.hasBell();
        if (needsFoundingCenter) {
            if (world.getTime() < task.nextMaterialSearch) return;
            if (!ensureItemCountAvailable(world, villager, task, Items.GOLD_BLOCK,
                    1, 0, new HashSet<>())) {
                // A recursive ingredient request may already have selected coal,
                // raw gold or gold ingots. Preserve that exact next requirement.
                if (task.stage != Stage.SMELT_MATERIAL) {
                    if (task.pendingMaterialItem == null) {
                        task.pendingMaterialItem = Items.GOLD_BLOCK;
                        task.pendingMaterialCount = 1;
                    }
                    task.resumeStage = Stage.BUILD;
                    if (task.materialReturnStage == null) task.materialReturnStage = Stage.BUILD;
                    task.stage = Stage.GATHER_MATERIAL;
                    task.moveTarget = null;
                    task.requestedMoveTarget = null;
                }
                return;
            }
            if (!buildMeetingPointIfNeeded(world, saved, village)) {
                // Keep the gold block in inventory and retry when nearby villagers
                // or mobs have cleared the centre's placement volume.
                task.nextMaterialSearch = world.getTime() + 20L;
                return;
            }
            if (!consumeItems(villager, Items.GOLD_BLOCK, 1)) {
                task.pendingMaterialItem = Items.GOLD_BLOCK;
                task.pendingMaterialCount = 1;
                task.resumeStage = Stage.BUILD;
                if (task.materialReturnStage == null) task.materialReturnStage = Stage.BUILD;
                task.stage = Stage.GATHER_MATERIAL;
                return;
            }
        }

        if (village != null) {
            village.registerHouse(task.site, task.professional);
            saved.markDirty();
        }

        boolean founderNeedsJobHouse = village != null && village.isFounding()
                && village.needsFoundingProfessional() && village.isFounder(villager.getUuid());
        if (task.homeBed != null) {
            // The founding villager keeps its first home while constructing the
            // adjacent profession house, so the night schedule can send it to bed.
            villager.getBrain().remember(MemoryModuleType.HOME,
                    GlobalPos.create(world.getRegistryKey(), task.homeBed));
        } else {
            villager.getBrain().remember(MemoryModuleType.HOME,
                    GlobalPos.create(world.getRegistryKey(), task.site.up()));
        }

        villager.getNavigation().stop();
        restoreBuilderProfession(villager, task);
        if (task.professional && task.targetProfessionId != null) {
            Registries.VILLAGER_PROFESSION.getOrEmpty(task.targetProfessionId).ifPresent(profession -> {
                villager.setVillagerData(villager.getVillagerData().withProfession(profession));
                BlockPos jobSite = task.workstationPos == null
                        ? task.site.add(task.footprintX / 2, 0, task.footprintZ / 2)
                        : task.workstationPos;
                villager.getBrain().remember(MemoryModuleType.JOB_SITE,
                        GlobalPos.create(world.getRegistryKey(), jobSite));
            });
        }
        villager.equipStack(EquipmentSlot.MAINHAND, task.originalMainHand.copy());
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                task.site.getX() + 0.5D, task.site.getY() + 4.0D, task.site.getZ() + 0.5D,
                8, 1.2D, 0.8D, 1.2D, 0.05D);
        world.playSound(null, task.site, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.9F, 1.0F);

        clearDebugLabel(villager);
        unregisterActiveTask(villager.getUuid());
        saved.removeActiveTask(villager.getUuid());
        RETRY_AT.put(villager.getUuid(), world.getTime() + (founderNeedsJobHouse ? 100L : 24_000L));
        if (founderNeedsJobHouse) {
            VillageExpansionMod.LOGGER.info("Villager {} completed the starter home at {} and will build the founding job-site house next.",
                    uuidShort(villager.getUuid()), task.site);
        } else if (task.foundingProject && village != null && !village.isFounding()) {
            VillageExpansionMod.LOGGER.info("Villager {} founded a settlement with its first home, professional house and meeting bell.",
                    uuidShort(villager.getUuid()));
        }
        VillageExpansionMod.LOGGER.info("Villager {} completed a {} biome house at {}. Village totals: {} ordinary, {} professional.",
                uuidShort(villager.getUuid()), task.professional ? "professional" : "ordinary", task.site,
                village == null ? "?" : village.commonHouses(),
                village == null ? "?" : village.professionalHouses());
    }

    private static void abandonTask(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        clearBreakingProgress(world, villager, task);
        villager.getNavigation().stop();
        restoreBuilderProfession(villager, task);
        villager.equipStack(EquipmentSlot.MAINHAND, task.originalMainHand.copy());
        clearDebugLabel(villager);
        unregisterActiveTask(villager.getUuid());
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        saved.removeActiveTask(villager.getUuid());
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
        CLEAR_PATH("Migrando limpeza antiga"),
        LEVEL_SITE("Limpando e nivelando lote"),
        BUILD_PATH("Construindo caminho"),
        GATHER_WOOD("Coletando madeira"),
        GATHER_DIRT("Coletando terra"),
        GATHER_STONE("Coletando pedra"),
        GATHER_MATERIAL("Coletando materiais"),
        GATHER_FROM_PROFESSIONAL("Obtendo materiais com profissionais"),
        SMELT_MATERIAL("Fundindo materiais"),
        MINE_TUNNEL("Escavando mina"),
        BUILD("Construindo casa");
        private final String debugName;
        Stage(String debugName) { this.debugName = debugName; }
    }

    private static final class BuilderTask {
        private final RegistryKey<World> dimension;
        private final long villageId;
        private final BlockPos site;
        private final boolean professional;
        private final boolean foundingProject;
        private final ItemStack originalMainHand;
        private final VillagerData originalVillagerData;
        private final List<BlockPos> treeTargets = new ArrayList<>();
        private final List<BlockPos> plotTreeTargets = new ArrayList<>();
        private int plotTreeIndex;
        private final List<BlockPos> pathBlocks = new ArrayList<>();
        private final List<LevelOperation> levelOperations = new ArrayList<>();
        private List<BlockPlacement> blueprint = new ArrayList<>();
        private final String templateId;
        private BlockPos homeBed;
        private int footprintX = 5;
        private int footprintZ = 5;

        private Stage stage = Stage.LEVEL_SITE;
        private int treeIndex;
        private int levelIndex;
        private int pathIndex;
        private int buildIndex;
        private int coverIndex;
        private int wood;
        private int stone;
        private int dirt;
        private int requiredWood = VillageExpansionConfig.woodRequired;
        private int requiredStone = VillageExpansionConfig.stoneRequired;
        // Stores the blueprint quota while one temporary stone block is gathered for terrain fill.
        private int temporaryStoneRequirement = -1;
        private int woodPiecesPlaced;
        private int actionTimer;
        private long nextResourceSearch;
        private BlockPos moveTarget;
        private BlockPos requestedMoveTarget;
        private double lastNavigationDistance = Double.MAX_VALUE;
        private long lastNavigationProgressAt;
        private long lastNavigationCommandAt;
        private long lastBrainNavigationOverride;
        private long nextBrainNavigationCheckAt;
        private int navigationRepathCount;
        private int navigationFallbackIndex;
        private int resourceSearchFailures;
        private int stoneSearchFailures;
        private int requiredDirt;
        private Stage resumeStage;
        private Identifier targetProfessionId;
        private BlockPos workstationPos;
        private ResourceTarget currentResource;
        private MiningTarget currentMine;
        private boolean plotVegetationCleared;
        private int plotTreeScanCursor;
        private boolean plotTreeScanComplete;
        private int plotPlantScanCursor;
        private long pausedUntil;
        private boolean sleepingAtNight;
        private BlockPos breakingPos;
        private int breakingTicks;
        private int breakingDuration;
        private BlockPos currentTreeBase;
        private final List<BlockPos> currentTreeLogs = new ArrayList<>();
        private int currentTreeLogIndex;
        private Item currentTreeLogItem;
        private final List<BlockPos> currentTreeLeaves = new ArrayList<>();
        private final ArrayDeque<BlockPos> currentTreeScanQueue = new ArrayDeque<>();
        private final Set<Long> currentTreeScanVisited = new HashSet<>();
        private boolean currentTreeScanComplete;
        private BlockPos currentTreeStandTarget;
        private BlockPos currentTreeStandPosition;
        private boolean currentTreeStandChecked;
        private int currentTreeLeafIndex;
        private boolean currentTreeLeavesScanned;
        private int smeltTicksRemaining;
        private int smeltFuelTicksRemaining;
        private int smeltOutputCount = 1;
        private Item pendingSmeltOutput;
        private Item pendingMaterialItem;
        private Item planBlockedItem;
        private Stage materialReturnStage;
        private int pendingMaterialCount = 1;
        private Item deferredMaterialItem;
        private int deferredMaterialCount;
        private final List<MaterialGoal> materialGoalStack = new ArrayList<>();
        // In-memory resumable plans keyed by requested output. Recipe references
        // are rebuilt after reload, while the public task goal remains persisted.
        private final Map<Item, MaterialPlanningJob> lazyMaterialPlans = new HashMap<>();
        private BlockPos saplingSearchOrigin;
        private long nextMaterialSearch;
        // Runtime-only scheduling fields. Vanilla Brain/navigation still ticks every tick.
        private long nextControllerUpdateAt;
        private long lastControllerUpdateAt = Long.MIN_VALUE;
        private long nextTaskSaveAt;
        private long nextDangerCheckAt;
        private long nextSaplingCheckAt;
        private int elapsedControllerTicks = 1;
        private boolean cachedNearbyDanger;
        private String lastDebugLabel;
        private Map<Item, Integer> remainingBlueprintCounts;
        private int remainingBlueprintCountsCursor = -1;
        private boolean remainingBlueprintCountsReady;
        private BlockPos constructionStandTarget;
        private BlockPos cachedConstructionStand;
        private BlockPos levelWorkStand;
        private final Set<Long> rejectedLevelWorkStands = new HashSet<>();
        private int levelStandSearchFailures;
        private BlockPos failedNavigationTarget;

        // Persistent state for visits to employed Villager suppliers. These fields
        // are serialized in writeTask()/restoreTask(); keeping them on BuilderTask
        // makes the supplier visit survive save/reload and lets failed providers be
        // temporarily excluded without interrupting the material objective.
        private UUID professionalProviderUuid;
        private long professionalVisitStartedAt;
        private UUID rejectedProfessionalProviderUuid;
        private Item rejectedProfessionalItem;
        private long rejectedProfessionalUntil;

        private BlockPos mineEntrance;
        // A bounded, remembered quarry prevents random isolated holes across a ravine.
        private BlockPos quarryCenter;
        private BlockPos mineFace;
        private Direction mineDirection;
        private int mineSteps;

        private BuilderTask(RegistryKey<World> dimension, long villageId, BlockPos site,
                            boolean professional, boolean foundingProject,
                            ItemStack originalMainHand, VillagerData originalVillagerData, String templateId) {
            this.dimension = dimension;
            this.villageId = villageId;
            this.site = site.toImmutable();
            this.professional = professional;
            this.foundingProject = foundingProject;
            this.originalMainHand = originalMainHand;
            this.originalVillagerData = originalVillagerData;
            this.templateId = templateId;
        }
    }

    private record LevelOperation(BlockPos pos, boolean fill) {
    }

    private record BlockPlacement(BlockPos pos, BlockState state) {
    }

    private record MaterialGoal(Item item, int count) {
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
