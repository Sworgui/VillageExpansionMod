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
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
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
    private static final Map<UUID, Long> RETRY_AT = new HashMap<>();

    private VillageBuilderController() {
    }

    public static void tickWorld(ServerWorld world) {
        if (!VillageExpansionConfig.enabled) return;
        if (world.getTime() % VillageExpansionConfig.taskTickInterval != 0L) return;

        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        List<VillagerEntity> villagers = new ArrayList<>();
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof VillagerEntity villager) villagers.add(villager);
        }

        // Restore every saved worker before assigning new jobs. This prevents a
        // loaded world from temporarily exceeding maxBuildersPerVillage while
        // entities are being iterated in an arbitrary order.
        for (VillagerEntity villager : villagers) {
            UUID uuid = villager.getUuid();
            if (ACTIVE_TASKS.containsKey(uuid)) continue;
            NbtCompound taskData = saved.getActiveTask(uuid);
            if (taskData == null) continue;
            BuilderTask restored = restoreTask(world, villager, taskData);
            if (restored != null) {
                ACTIVE_TASKS.put(uuid, restored);
                removeOriginalProfessionTags(villager);
                applyBuilderProfession(villager, restored);
            } else {
                saved.removeActiveTask(uuid);
            }
        }

        for (VillagerEntity villager : villagers) {
            UUID uuid = villager.getUuid();
            BuilderTask task = ACTIVE_TASKS.get(uuid);
            if (task != null) {
                if (!villager.isAlive() || villager.isRemoved() || !task.dimension.equals(world.getRegistryKey())) {
                    abandonTask(world, villager, task);
                    continue;
                }
                tickTask(world, villager, task);
                // A task may complete or be abandoned during tickTask. Never
                // resurrect its NBT snapshot after completeProject removed it.
                if (ACTIVE_TASKS.get(uuid) == task && world.getTime() % 20L == 0L) {
                    saved.saveActiveTask(uuid, writeTask(task));
                }
                continue;
            }

            restoreOrphanedBuilderProfession(villager);
            if (world.getTime() % VillageExpansionConfig.assignmentScanInterval == 0L) {
                tryAssignBuilder(world, villager);
            }
        }

        if (world.getTime() % 200L == 0L) updateFoundingCompletion(world, saved);
    }

    /**
     * Called immediately after the vanilla Brain tick. The Brain is never disabled;
     * construction navigation simply gets the final movement command while work is active.
     */
    public static void afterVanillaBrainTick(VillagerEntity villager) {
        BuilderTask task = ACTIVE_TASKS.get(villager.getUuid());
        if (task == null || !(villager.getWorld() instanceof ServerWorld serverWorld)) return;
        long now = serverWorld.getTime();
        if (nearbyDanger(serverWorld, villager) || healthEmergency(villager) || now < task.pausedUntil
                || task.sleepingAtNight || nightWithAssignedHome(villager)) return;

        if (task.moveTarget == null) {
            villager.getNavigation().stop();
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
        } else if (task.pendingMaterialItem != null
                && (task.stage == Stage.GATHER_MATERIAL
                    || (task.stage == Stage.GATHER_STONE && task.resumeStage == Stage.GATHER_MATERIAL))) {
            String itemName = Registries.ITEM.getId(task.pendingMaterialItem).getPath();
            label = "Faltando: " + itemName + " x" + Math.max(1, task.pendingMaterialCount);
        } else {
            label = task.stage.debugName;
        }
        villager.setCustomName(Text.literal(DEBUG_LABEL_PREFIX + label));
        villager.setCustomNameVisible(true);
    }

    private static void clearDebugLabel(VillagerEntity villager) {
        if (villager.getCustomName() != null && villager.getCustomName().getString().startsWith(DEBUG_LABEL_PREFIX)) {
            villager.setCustomName(null);
            villager.setCustomNameVisible(false);
        }
    }

    private static void tickTask(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        long now = world.getTime();
        updateDebugLabel(villager, task);

        if (nearbyDanger(world, villager) || healthEmergency(villager)) {
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

        if (world.getTime() % VillageExpansionConfig.particleIntervalTicks == 0L) {
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    villager.getX(), villager.getY() + 1.75D, villager.getZ(),
                    2, 0.22D, 0.12D, 0.22D, 0.0D);
        }
        // Leaves decay naturally after the trunk is removed. Periodically collect
        // only sapling item drops and replant them; leaf blocks are never harvested.
        if (VillageExpansionConfig.replantSaplings && now % 200L == 0L) {
            BlockPos origin = task.saplingSearchOrigin == null ? villager.getBlockPos() : task.saplingSearchOrigin;
            replantSaplings(world, villager, task, origin);
        }

        if (task.actionTimer > 0) task.actionTimer--;

        switch (task.stage) {
            case CLEAR_PATH -> tickClearPath(world, villager, task);
            case LEVEL_SITE -> tickLevelSite(world, villager, task);
            case BUILD_PATH -> tickBuildPath(world, villager, task);
            case GATHER_WOOD -> tickGatherWood(world, villager, task);
            case GATHER_DIRT -> tickGatherDirt(world, villager, task);
            case GATHER_STONE -> tickGatherStone(world, villager, task);
            case GATHER_MATERIAL -> tickGatherMaterial(world, villager, task);
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

        if (countActiveBuilders(world, village.id()) >= VillageExpansionConfig.maxBuildersPerVillage) {
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
            RETRY_AT.put(villager.getUuid(), world.getTime() + VillageExpansionConfig.retryDelayTicks);
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
        task.treeTargets.addAll(findTreesAlongPath(world, task.pathBlocks, site, sizeX, sizeZ));
        task.levelOperations.addAll(createLevelOperations(world, site, sizeX, sizeZ));
        ACTIVE_TASKS.put(villager.getUuid(), task);
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
        nbt.putInt("breakingTicks", task.breakingTicks);
        nbt.putInt("breakingDuration", task.breakingDuration);
        if (task.breakingPos != null) nbt.putLong("breakingPos", task.breakingPos.asLong());
        if (task.mineEntrance != null) nbt.putLong("mineEntrance", task.mineEntrance.asLong());
        if (task.mineFace != null) nbt.putLong("mineFace", task.mineFace.asLong());
        nbt.putInt("mineDirection", task.mineDirection == null ? -1 : task.mineDirection.getId());
        nbt.putInt("mineSteps", task.mineSteps);
        nbt.putString("targetProfession", task.targetProfessionId == null ? "" : task.targetProfessionId.toString());
        if (task.workstationPos != null) nbt.putLong("workstationPos", task.workstationPos.asLong());
        nbt.putString("resumeStage", task.resumeStage == null ? "" : task.resumeStage.name());
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
        if (task.pendingMaterialItem != null) nbt.putString("pendingMaterialItem", Registries.ITEM.getId(task.pendingMaterialItem).toString());
        nbt.putInt("pendingMaterialCount", task.pendingMaterialCount);
        if (task.deferredMaterialItem != null) {
            nbt.putString("deferredMaterialItem", Registries.ITEM.getId(task.deferredMaterialItem).toString());
            nbt.putInt("deferredMaterialCount", task.deferredMaterialCount);
        }
        if (task.saplingSearchOrigin != null) nbt.putLong("saplingSearchOrigin", task.saplingSearchOrigin.asLong());
        if (task.currentTreeBase != null) nbt.putLong("currentTreeBase", task.currentTreeBase.asLong());
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
            task.breakingTicks = nbt.getInt("breakingTicks");
            task.breakingDuration = nbt.getInt("breakingDuration");
            if (nbt.contains("breakingPos")) task.breakingPos = BlockPos.fromLong(nbt.getLong("breakingPos"));
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
            Identifier pendingMaterialId = Identifier.tryParse(nbt.getString("pendingMaterialItem"));
            if (pendingMaterialId != null) task.pendingMaterialItem = Registries.ITEM.getOrEmpty(pendingMaterialId).orElse(null);
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
            if (nbt.contains("saplingSearchOrigin", NbtElement.LONG_TYPE)) task.saplingSearchOrigin = BlockPos.fromLong(nbt.getLong("saplingSearchOrigin"));
            task.currentTreeLeaves.addAll(readPositionList(nbt, "currentTreeLeaves"));
            if (nbt.contains("currentTreeBase", NbtElement.LONG_TYPE)) {
                task.currentTreeBase = BlockPos.fromLong(nbt.getLong("currentTreeBase"));
            }
            task.treeTargets.addAll(readPositionList(nbt, "treeTargets"));
            task.pathBlocks.addAll(readPositionList(nbt, "pathBlocks"));
            task.currentTreeLogs.addAll(readPositionList(nbt, "currentTreeLogs"));
            task.plotTreeTargets.addAll(readPositionList(nbt, "plotTreeTargets"));
            task.plotTreeIndex = nbt.getInt("plotTreeIndex");
            task.levelOperations.addAll(readLevelOperations(nbt));
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
            if (!nbt.contains("treeTargets")) {
                task.treeTargets.addAll(findTreesAlongPath(world, task.pathBlocks, site, sx, sz));
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

    private static BlockPos findBuildSite(ServerWorld world, VillageExpansionState.VillageRecord village,
                                           int sizeX, int sizeZ) {
        BlockPos center = village.center();
        for (int radius = VillageExpansionConfig.houseSearchMinRadius;
             radius <= VillageExpansionConfig.houseSearchMaxRadius;
             radius += VillageExpansionConfig.houseSearchStep) {
            for (int angleStep = 0; angleStep < 24; angleStep++) {
                double angle = (Math.PI * 2.0D * angleStep) / 24.0D;
                int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
                BlockPos candidate = createViableSite(world, x, z, sizeX, sizeZ);
                if (candidate == null || village.isNearExistingHouse(candidate, VillageExpansionConfig.houseSpacing)
                        || village.isNearMine(candidate, VillageExpansionConfig.mineExclusionRadius)
                        || isBuildSiteReserved(world, village.id(), candidate)) continue;
                return candidate;
            }
        }
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
                int groundY = findSurfaceY(world, x, z);
                if (groundY == Integer.MIN_VALUE) return null;
                BlockPos above = new BlockPos(x, groundY + 1, z);
                if (!world.getFluidState(above).isEmpty()) return null;
                groundHeights.add(groundY);
                minY = Math.min(minY, groundY);
                maxY = Math.max(maxY, groundY);
            }
        }
        if (maxY - minY > 1) return null;
        groundHeights.sort(Integer::compareTo);
        int baseY = groundHeights.get(groundHeights.size() / 2);

        for (int x = centerX - halfX; x < centerX - halfX + sizeX; x++) {
            for (int z = centerZ - halfZ; z < centerZ - halfZ + sizeZ; z++) {
                int surfaceY = findSurfaceY(world, x, z);
                for (int y = surfaceY + 1; y <= baseY + 10; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!world.getFluidState(pos).isEmpty()) return null;
                    if (state.isAir() || state.isIn(BlockTags.LEAVES) || isPlant(state)) continue;
                    if (state.isIn(BlockTags.LOGS) && hasNearbyLeaves(world, pos)) continue;
                    if (state.hasBlockEntity() || state.isSolidBlock(world, pos)) return null;
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
        int maxExpanded = VillageExpansionConfig.maxPathSearchNodes;
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

    private static List<BlockPos> findTreesAlongPath(ServerWorld world, List<BlockPos> path, BlockPos site,
                                                     int sizeX, int sizeZ) {
        Set<BlockPos> unique = new HashSet<>();
        for (BlockPos pathBlock : path) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    addTreeBasesAtColumn(world, pathBlock.getX() + dx, pathBlock.getZ() + dz, unique);
                }
            }
        }
        for (int x = site.getX() - 1; x <= site.getX() + sizeX; x++) {
            for (int z = site.getZ() - 1; z <= site.getZ() + sizeZ; z++) {
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
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = 2; dy <= 14; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos canopy = root.add(dx, dy, dz);
                    if (!isLoaded(world, canopy)) continue;
                    BlockState leafState = world.getBlockState(canopy);
                    if (!leafState.isIn(BlockTags.LEAVES)) continue;
                    // Vanilla player-placed leaves are persistent; naturally generated
                    // tree canopies use non-persistent leaves and decay when disconnected.
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
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 8; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
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
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 12; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos check = origin.add(dx, dy, dz);
                    if (isLoaded(world, check) && world.getBlockState(check).isIn(BlockTags.LEAVES)) return true;
                }
            }
        }
        return false;
    }

    private static List<LevelOperation> createLevelOperations(ServerWorld world, BlockPos site, int sizeX, int sizeZ) {
        List<LevelOperation> cuts = new ArrayList<>();
        List<LevelOperation> fills = new ArrayList<>();

        for (int x = site.getX(); x < site.getX() + sizeX; x++) {
            for (int z = site.getZ(); z < site.getZ() + sizeZ; z++) {
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

    private static void tickClearPath(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        while (task.treeIndex < task.treeTargets.size()
                && !(task.currentTreeBase != null && task.currentTreeBase.equals(task.treeTargets.get(task.treeIndex)))
                && !isNaturalTreeRoot(world, task.treeTargets.get(task.treeIndex))) {
            task.treeIndex++;
        }
        if (task.treeIndex >= task.treeTargets.size()) {
            villager.getNavigation().stop();
            task.stage = Stage.LEVEL_SITE;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            return;
        }
        BlockPos treeBase = task.treeTargets.get(task.treeIndex);
        if (tickCutTree(world, villager, task, treeBase)) {
            task.wood = countInventoryItems(villager, ItemTags.LOGS);
            task.treeIndex++;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
        }
    }

    /** Breaks only the connected logs of one confirmed natural tree. Leaves decay naturally. */
    private static boolean tickCutTree(ServerWorld world, VillagerEntity villager, BuilderTask task, BlockPos base) {
        BlockPos naturalRoot;
        if (task.currentTreeBase != null && task.currentTreeBase.equals(base)) {
            // The root itself may already have been broken. Continue the persisted tree worklist.
            naturalRoot = task.currentTreeBase;
        } else {
            naturalRoot = findNaturalTreeRoot(world, base);
            if (naturalRoot == null || isProtectedByNearbyBuilding(world, naturalRoot)) {
                clearBreakingProgress(world, villager, task);
                return true; // Never fell a structure or an isolated decorative log.
            }
        }
        base = naturalRoot;
        // Retry pickup while processing this tree. If the inventory was temporarily full,
        // later block drops can still be collected after space becomes available.
        collectDrops(world, villager, base, ItemTags.LOGS);
        collectDrops(world, villager, base, ItemTags.SAPLINGS);
        final BlockPos treeOrigin = base;
        if (task.currentTreeBase == null || !task.currentTreeBase.equals(base)) {
            clearBreakingProgress(world, villager, task);
            task.currentTreeBase = base.toImmutable();
            task.currentTreeLogItem = world.getBlockState(base).getBlock().asItem();
            task.currentTreeLogs.clear();
            task.currentTreeLeaves.clear();
            task.currentTreeLogIndex = 0;
            task.currentTreeLeafIndex = 0;
            task.currentTreeLeavesScanned = false;
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> visited = new HashSet<>();
            queue.add(base);
            visited.add(base);
            while (!queue.isEmpty() && task.currentTreeLogs.size() < 512) {
                BlockPos current = queue.removeFirst();
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
                            // Once the root is verified as a natural tree, follow every
                            // connected log in the bounded tree volume. Requiring leaves
                            // next to each log truncated tall trunks before reaching the canopy.
                            if (visited.add(neighbor) && isLoaded(world, neighbor)
                                    && world.getBlockState(neighbor).isIn(BlockTags.LOGS)) queue.addLast(neighbor);
                        }
                    }
                }
            }
            task.currentTreeLogs.sort(Comparator.comparingInt(BlockPos::getY)
                    .thenComparingDouble(pos -> pos.getSquaredDistance(treeOrigin)));
        }

        while (task.currentTreeLogIndex < task.currentTreeLogs.size()) {
            BlockPos log = task.currentTreeLogs.get(task.currentTreeLogIndex);
            if (!world.getBlockState(log).isIn(BlockTags.LOGS)) {
                task.currentTreeLogIndex++;
                continue;
            }
            BlockPos stand = findStandingPosition(world, log, villager);
            if (stand == null) stand = findStandingPosition(world, base, villager);
            if (stand == null || !moveNear(villager, task, stand, VillageExpansionConfig.navigationSpeed)) return false;
            equipBestTool(villager, ToolKind.AXE);
            if (!tickBreakBlock(world, villager, task, log, ToolKind.AXE, ItemTags.LOGS)) return false;
            task.currentTreeLogIndex++;
            task.wood = countInventoryItems(villager, ItemTags.LOGS);
            return false;
        }

        // Do not break leaves. Vanilla leaf decay will generate sapling drops
        // naturally after the connected logs have been removed.
        task.saplingSearchOrigin = base.toImmutable();
        collectDrops(world, villager, base, ItemTags.LOGS);
        // Saplings may drop several seconds after the tree is felled. The
        // periodic sapling pass in tickTask will collect and replant them.
        task.currentTreeBase = null;
        task.currentTreeLogItem = null;
        task.currentTreeLogs.clear();
        task.currentTreeLeaves.clear();
        task.currentTreeLogIndex = 0;
        task.currentTreeLeafIndex = 0;
        task.currentTreeLeavesScanned = false;
        clearBreakingProgress(world, villager, task);
        task.wood = countInventoryItems(villager, ItemTags.LOGS);
        return true;
    }

    private static void replantSaplings(ServerWorld world, VillagerEntity villager, BuilderTask task, BlockPos origin) {
        if (!VillageExpansionConfig.replantSaplings) return;

        // First replant saplings already collected into the worker inventory.
        for (int slot = 0; slot < villager.getInventory().size(); slot++) {
            ItemStack stack = villager.getInventory().getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.SAPLINGS)) continue;
            Block saplingBlock = getSaplingBlock(stack.getItem());
            if (saplingBlock == Blocks.AIR) continue;
            while (!stack.isEmpty()) {
                BlockPos plantingSpot = findSaplingPlantingSpot(world, task, origin, saplingBlock);
                if (plantingSpot == null || !world.setBlockState(plantingSpot,
                        saplingBlock.getDefaultState(), Block.NOTIFY_ALL)) break;
                stack.decrement(1);
                villager.getInventory().markDirty();
                world.playSound(null, plantingSpot, SoundEvents.BLOCK_GRASS_PLACE,
                        SoundCategory.BLOCKS, 0.35F, 1.1F);
            }
        }

        // If the Villager's eight inventory slots were full when a leaf dropped a
        // sapling, plant nearby sapling item entities directly instead of leaving
        // them permanently on the ground.
        int radius = VillageExpansionConfig.saplingPlantRadius;
        Box dropArea = new Box(origin.getX() - radius, origin.getY() - 16.0D, origin.getZ() - radius,
                origin.getX() + radius + 1.0D, origin.getY() + 18.0D, origin.getZ() + radius + 1.0D);
        for (ItemEntity entity : world.getEntitiesByClass(ItemEntity.class, dropArea,
                item -> !item.isRemoved() && item.getStack().isIn(ItemTags.SAPLINGS))) {
            ItemStack stack = entity.getStack();
            Block saplingBlock = getSaplingBlock(stack.getItem());
            if (saplingBlock == Blocks.AIR) continue;
            boolean plantedAny = false;
            while (!stack.isEmpty()) {
                BlockPos plantingSpot = findSaplingPlantingSpot(world, task, origin, saplingBlock);
                if (plantingSpot == null || !world.setBlockState(plantingSpot,
                        saplingBlock.getDefaultState(), Block.NOTIFY_ALL)) break;
                stack.decrement(1);
                plantedAny = true;
                world.playSound(null, plantingSpot, SoundEvents.BLOCK_GRASS_PLACE,
                        SoundCategory.BLOCKS, 0.35F, 1.1F);
            }
            if (stack.isEmpty()) entity.discard();
            else if (plantedAny) entity.setStack(stack);
        }
    }

    private static Block getSaplingBlock(Item saplingItem) {
        Block block = Registries.BLOCK.get(Registries.ITEM.getId(saplingItem));
        return block != Blocks.AIR && block.asItem().equals(saplingItem) ? block : Blocks.AIR;
    }

    private static BlockPos findSaplingPlantingSpot(ServerWorld world, BuilderTask task,
                                                    BlockPos origin, Block saplingBlock) {
        int radius = VillageExpansionConfig.saplingPlantRadius;
        List<BlockPos> candidates = new ArrayList<>();
        List<BlockPos> existingSaplings = new ArrayList<>();
        int scanRadius = radius + VillageExpansionConfig.saplingMinimumSpacing;
        for (int sx = -scanRadius; sx <= scanRadius; sx++) {
            for (int sz = -scanRadius; sz <= scanRadius; sz++) {
                int x = origin.getX() + sx;
                int z = origin.getZ() + sz;
                int surfaceY = findSurfaceY(world, x, z);
                if (surfaceY == Integer.MIN_VALUE) continue;
                for (int sy = -3; sy <= 5; sy++) {
                    BlockPos check = new BlockPos(x, surfaceY + sy, z);
                    if (isLoaded(world, check) && world.getBlockState(check).isIn(BlockTags.SAPLINGS)) {
                        existingSaplings.add(check.toImmutable());
                    }
                }
            }
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int groundY = findSurfaceY(world, x, z);
                if (groundY == Integer.MIN_VALUE) continue;
                BlockPos ground = new BlockPos(x, groundY, z);
                BlockPos plantPos = ground.up();
                if (!isLoaded(world, plantPos) || !world.getBlockState(ground).isOf(Blocks.DIRT)
                        && !world.getBlockState(ground).isOf(Blocks.GRASS_BLOCK)
                        && !world.getBlockState(ground).isOf(Blocks.PODZOL)
                        && !world.getBlockState(ground).isOf(Blocks.COARSE_DIRT)
                        || !world.getBlockState(plantPos).isAir()
                        || world.getLightLevel(plantPos) < 8
                        || !saplingBlock.getDefaultState().canPlaceAt(world, plantPos)) continue;
                if (x >= task.site.getX() - 2 && x < task.site.getX() + task.footprintX + 2
                        && z >= task.site.getZ() - 2 && z < task.site.getZ() + task.footprintZ + 2) continue;
                boolean nearPath = false;
                for (BlockPos path : task.pathBlocks) {
                    if (Math.abs(path.getX() - x) <= 1 && Math.abs(path.getZ() - z) <= 1) { nearPath = true; break; }
                }
                if (nearPath) continue;
                boolean tooClose = false;
                for (BlockPos other : existingSaplings) {
                    if (other.getSquaredDistance(plantPos) < (double) VillageExpansionConfig.saplingMinimumSpacing * VillageExpansionConfig.saplingMinimumSpacing) { tooClose = true; break; }
                }
                if (!tooClose) candidates.add(plantPos.toImmutable());
            }
        }
        return candidates.isEmpty() ? null : candidates.get(world.random.nextInt(candidates.size()));
    }

    private enum ToolKind { AXE, PICKAXE, SHOVEL }

    private static Item toolItem(ToolKind kind, int tier) {
        return switch (kind) {
            case AXE -> tier >= 2 ? Items.IRON_AXE : tier == 1 ? Items.STONE_AXE : Items.WOODEN_AXE;
            case PICKAXE -> tier >= 2 ? Items.IRON_PICKAXE : tier == 1 ? Items.STONE_PICKAXE : Items.WOODEN_PICKAXE;
            case SHOVEL -> tier >= 2 ? Items.IRON_SHOVEL : tier == 1 ? Items.STONE_SHOVEL : Items.WOODEN_SHOVEL;
        };
    }

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
        if (bestTier < 0) equipForWork(villager, toolItem(kind, 0));
        else if (!villager.getEquippedStack(EquipmentSlot.MAINHAND).isOf(best.getItem())) {
            if (bestSlot >= 0) {
                villager.getInventory().getStack(bestSlot).decrement(1);
                villager.getInventory().markDirty();
            }
            villager.equipStack(EquipmentSlot.MAINHAND, best);
        }
        tryUpgradeAtBlacksmith(villager, kind);
    }

    private static int toolTier(Item item, ToolKind kind) {
        return switch (kind) {
            case AXE -> item == Items.NETHERITE_AXE ? 5 : item == Items.DIAMOND_AXE ? 4
                    : item == Items.IRON_AXE ? 3 : item == Items.GOLDEN_AXE ? 2
                    : item == Items.STONE_AXE ? 1 : item == Items.WOODEN_AXE ? 0 : -1;
            case PICKAXE -> item == Items.NETHERITE_PICKAXE ? 5 : item == Items.DIAMOND_PICKAXE ? 4
                    : item == Items.IRON_PICKAXE ? 3 : item == Items.GOLDEN_PICKAXE ? 2
                    : item == Items.STONE_PICKAXE ? 1 : item == Items.WOODEN_PICKAXE ? 0 : -1;
            case SHOVEL -> item == Items.NETHERITE_SHOVEL ? 5 : item == Items.DIAMOND_SHOVEL ? 4
                    : item == Items.IRON_SHOVEL ? 3 : item == Items.GOLDEN_SHOVEL ? 2
                    : item == Items.STONE_SHOVEL ? 1 : item == Items.WOODEN_SHOVEL ? 0 : -1;
        };
    }

    private static void tryUpgradeAtBlacksmith(VillagerEntity villager, ToolKind kind) {
        if (!VillageExpansionConfig.blacksmithToolUpgrades) return;
        if (toolTier(villager.getEquippedStack(EquipmentSlot.MAINHAND).getItem(), kind) >= 3) return;
        if (countSpecificItem(villager, Items.IRON_INGOT) < 3) return;
        Box area = villager.getBoundingBox().expand(18.0D);
        boolean smith = villager.getWorld().getEntitiesByClass(VillagerEntity.class, area,
                other -> other.isAlive() && (other.getVillagerData().getProfession().equals(VillagerProfession.ARMORER)
                        || other.getVillagerData().getProfession().equals(VillagerProfession.TOOLSMITH)
                        || other.getVillagerData().getProfession().equals(VillagerProfession.WEAPONSMITH))).size() > 0;
        if (!smith) return;
        if (consumeItems(villager, Items.IRON_INGOT, 3)) {
            villager.equipStack(EquipmentSlot.MAINHAND, new ItemStack(toolItem(kind, 2)));
        }
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
        task.breakingTicks++;
        if (task.breakingTicks % 5 == 0) villager.swingHand(Hand.MAIN_HAND);
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
            clearPlotVegetation(world, villager, task);
            task.plotVegetationCleared = true;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
        }

        while (task.plotTreeIndex < task.plotTreeTargets.size()
                && !isNaturalTreeRoot(world, task.plotTreeTargets.get(task.plotTreeIndex))
                && !(task.currentTreeBase != null
                && task.currentTreeBase.equals(task.plotTreeTargets.get(task.plotTreeIndex)))) {
            task.plotTreeIndex++;
        }
        if (task.plotTreeIndex < task.plotTreeTargets.size()) {
            BlockPos treeBase = task.plotTreeTargets.get(task.plotTreeIndex);
            if (tickCutTree(world, villager, task, treeBase)) {
                task.plotTreeIndex++;
                task.currentResource = null;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
            }
            return;
        }

        BlockPos workStand = new BlockPos(task.site.getX(), task.site.getY() + 1, task.site.getZ());
        if (!moveNear(villager, task, workStand, VillageExpansionConfig.navigationSpeed)) return;

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
        task.stage = Stage.BUILD_PATH;
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
            task.stage = Stage.BUILD;
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
                    if (treeStand == null || !moveNear(villager, task, treeStand, VillageExpansionConfig.navigationSpeed)) return;
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

    private static void clearPlotVegetation(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        BlockPos site = task.site;
        Set<BlockPos> roots = new HashSet<>();
        // Include trees rooted just outside the footprint whose leaves extend over the house.
        for (int x = site.getX() - 4; x < site.getX() + task.footprintX + 4; x++) {
            for (int z = site.getZ() - 4; z < site.getZ() + task.footprintZ + 4; z++) {
                int groundY = findSurfaceY(world, x, z);
                if (groundY == Integer.MIN_VALUE) continue;
                BlockPos root = new BlockPos(x, groundY + 1, z);
                if (isNaturalTreeRoot(world, root) && !isProtectedByNearbyBuilding(world, root)
                        && treeCanopyOverlapsPlot(world, root, task)) roots.add(root.toImmutable());
            }
        }
        task.plotTreeTargets.clear();
        task.plotTreeTargets.addAll(roots);
        task.plotTreeTargets.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(site)));
        task.plotTreeIndex = 0;

        for (int x = site.getX(); x < site.getX() + task.footprintX; x++) {
            for (int z = site.getZ(); z < site.getZ() + task.footprintZ; z++) {
                for (int y = site.getY() + 1; y <= site.getY() + 10; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    // Never destroy wood here. Confirmed trees are queued above and harvested
                    // one block at a time by tickCutTree, including their connected trunk logs.
                    if (state.isIn(BlockTags.LOGS)) continue;
                    if (state.isIn(BlockTags.LEAVES)) {
                        // Never harvest leaves. They are left in place to decay naturally
                        // after the trunk is felled; sapling drops are collected later.
                        continue;
                    } else if (isPlant(state)) {
                        if (world.breakBlock(pos, true, villager)) collectDrops(world, villager, pos, null);
                    }
                }
            }
        }
        if (task.saplingSearchOrigin != null) replantSaplings(world, villager, task, task.saplingSearchOrigin);
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
        for (int x = around.getX() - radius; x <= around.getX() + radius; x++) {
            for (int z = around.getZ() - radius; z <= around.getZ() + radius; z++) {
                int surfaceY = findSurfaceY(world, x, z);
                if (surfaceY == Integer.MIN_VALUE) continue;
                BlockPos block = new BlockPos(x, surfaceY, z);
                if (!isLoaded(world, block) || !world.getBlockState(block).isIn(BlockTags.DIRT)
                        || world.getBlockState(block).hasBlockEntity()) continue;
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
        for (int x = around.getX() - radius; x <= around.getX() + radius; x++) {
            for (int z = around.getZ() - radius; z <= around.getZ() + radius; z++) {
                int groundY = findSurfaceY(world, x, z);
                if (groundY == Integer.MIN_VALUE) continue;
                BlockPos root = new BlockPos(x, groundY + 1, z);
                if (!isNaturalTreeRoot(world, root) || isProtectedByNearbyBuilding(world, root)) continue;
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
        }
        return best;
    }

    private static void tickGatherStone(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        boolean exactStoneRequest = task.resumeStage == Stage.GATHER_MATERIAL && task.pendingMaterialItem != null;
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
            BlockPos searchFrom = task.mineFace == null ? task.site : task.mineFace;
            int caveRadius = expandedStoneSearchRadius(task.stoneSearchFailures);
            MiningTarget exposed = exactStoneRequest
                    ? findExposedCaveStone(world, searchFrom, villager, task.pendingMaterialItem, caveRadius)
                    : findExposedCaveStone(world, searchFrom, villager, caveRadius);
            if (exposed != null) {
                task.currentMine = exposed;
                // A successful scan breaks the streak of unsuccessful searches.
                task.stoneSearchFailures = 0;
                task.resourceSearchFailures = 0;
                task.moveTarget = null;
                task.requestedMoveTarget = null;
            } else {
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
        int verticalDepth = wantedDrop == Items.COAL
                ? Math.max(64, VillageExpansionConfig.caveSearchDepth)
                : VillageExpansionConfig.caveSearchDepth;
        int minY = Math.max(world.getBottomY() + 2, around.getY() - verticalDepth);
        // Coal can be exposed on mountain faces well above the villager's Y level.
        // Search upward as well as downward for coal-specific requests.
        int upwardRange = wantedDrop == Items.COAL ? Math.max(48, verticalDepth) : 4;
        int maxY = Math.min(world.getTopY() - 3, around.getY() + upwardRange);
        MiningTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = around.getX() - radius; x <= around.getX() + radius; x++) {
            for (int z = around.getZ() - radius; z <= around.getZ() + radius; z++) {
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
                        if (wantedDrop == null ? !isMineableStoneOrOre(rockState, villager)
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
        for (int dy = 2; dy <= 10; dy++) {
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
                    villager, task.pendingMaterialItem, 3);
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
        for (int radius = minimumDistance; radius <= searchLimit; radius += 4) {
            for (int i = 0; i < 32; i++) {
                double angle = i * (Math.PI * 2.0D / 32.0D);
                int x = villageCenter.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = villageCenter.getZ() + (int) Math.round(Math.sin(angle) * radius);
                int y = findSurfaceY(world, x, z);
                if (y == Integer.MIN_VALUE) continue;
                BlockPos ground = new BlockPos(x, y, z);
                // Enforce the real horizontal distance, not merely the ring index
                // (the trig samples are rounded to integer block coordinates).
                if (horizontalDistanceSquared(ground, villageCenter) < minimumDistanceSquared) continue;
                BlockPos feet = ground.up();
                BlockPos head = feet.up();
                if (!isLoaded(world, ground) || !isLoaded(world, head)
                        || !isTerrain(world.getBlockState(ground))) continue;
                if (!world.getBlockState(feet).isAir() || !world.getBlockState(head).isAir()
                        || !world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty()) continue;
                if (hasLivingEntityInBlock(world, feet, null) || hasLivingEntityInBlock(world, head, null)) continue;
                if (Math.abs(y - site.getY()) > 6) continue;
                if (knownMines.stream().anyMatch(mine -> mine.getSquaredDistance(ground) < 16.0D * 16.0D)) continue;
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
        if (isPlant(state)) return Items.AIR;
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
                    return !state.isIn(BlockTags.FLOWERS) && isPlant(state);
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

    /** Counts all still-placeable instances of one item in the unfinished part of the blueprint. */
    private static int countRemainingBlueprintItem(ServerWorld world, BuilderTask task, Item wanted) {
        int count = 0;
        for (int index = task.buildIndex; index < task.blueprint.size(); index++) {
            BlockPlacement placement = task.blueprint.get(index);
            BlockState existing = world.getBlockState(placement.pos());
            if (!existing.isAir() && !existing.isReplaceable()) continue;

            BlockState planned = placement.state();
            if (planned.getBlock() instanceof DoorBlock && planned.contains(DoorBlock.HALF)
                    && planned.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) continue;
            if (planned.getBlock() instanceof net.minecraft.block.BedBlock && planned.contains(BedBlock.PART)
                    && planned.get(BedBlock.PART) == BedPart.HEAD) continue;
            if (requiredItemForBlock(planned) == wanted) count++;
        }
        return count;
    }

    private static int remainingTaggedMaterialRequirement(ServerWorld world, VillagerEntity villager,
                                                           Map<Item, Integer> plan, TagKey<Item> tag) {
        int total = estimateRequiredTaggedMaterials(world, plan, tag, 0);
        int credit = countInventoryItems(villager, tag);
        for (Map.Entry<Item, Integer> entry : plan.entrySet()) {
            Item item = entry.getKey();
            if (new ItemStack(item).isIn(tag)) continue;
            int held = Math.min(entry.getValue(), countSpecificItem(villager, item));
            if (held > 0) credit += estimateTaggedCost(world, item, held, tag, 0, new HashSet<>());
        }
        return Math.max(0, total - credit);
    }

    private static int estimateRequiredTaggedMaterials(ServerWorld world, Map<Item, Integer> plan,
                                                        TagKey<Item> targetTag, int depth) {
        int total = 0;
        for (Map.Entry<Item, Integer> entry : plan.entrySet()) {
            total += estimateTaggedCost(world, entry.getKey(), entry.getValue(), targetTag, depth, new HashSet<>());
        }
        return total;
    }

    private static int estimateTaggedCost(ServerWorld world, Item item, int amount, TagKey<Item> targetTag,
                                          int depth, Set<Item> visited) {
        if (amount <= 0) return 0;
        if (new ItemStack(item).isIn(targetTag)) return amount;
        if (depth >= 8 || !visited.add(item)) return 0;
        for (RecipeEntry<CraftingRecipe> entry : world.getServer().getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != item) continue;
            int batches = (amount + output.getCount() - 1) / output.getCount();
            Map<Item, Integer> inputCounts = new HashMap<>();
            for (Ingredient ingredient : entry.value().getIngredients()) {
                if (ingredient.isEmpty()) continue;
                Item input = chooseIngredientItem(world, ingredient, null, targetTag);
                if (input != null) inputCounts.merge(input, batches, Integer::sum);
            }
            int total = 0;
            for (Map.Entry<Item, Integer> input : inputCounts.entrySet()) {
                total += estimateTaggedCost(world, input.getKey(), input.getValue(), targetTag, depth + 1, visited);
            }
            visited.remove(item);
            return total;
        }
        visited.remove(item);
        return 0;
    }

    private static Item chooseIngredientItem(ServerWorld world, Ingredient ingredient, VillagerEntity villager,
                                             TagKey<Item> preferredTag) {
        ItemStack[] candidates = ingredient.getMatchingStacks();
        if (candidates.length == 0) return null;
        if (villager != null) {
            for (ItemStack candidate : candidates) {
                if (countSpecificItem(villager, candidate.getItem()) > 0) return candidate.getItem();
            }
        }
        if (preferredTag != null) {
            for (ItemStack candidate : candidates) if (candidate.isIn(preferredTag)) return candidate.getItem();
        }
        for (ItemStack candidate : candidates) {
            if (hasCraftingRecipe(world, candidate.getItem())) return candidate.getItem();
        }
        return candidates[0].getItem();
    }

    private static boolean hasCraftingRecipe(ServerWorld world, Item outputItem) {
        for (RecipeEntry<CraftingRecipe> entry : world.getServer().getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (!output.isEmpty() && output.getItem() == outputItem) return true;
        }
        return false;
    }

    /** Uses registered crafting/smelting recipes and consumes only the exact ingredients. */
    private static boolean ensureItemAvailable(ServerWorld world, VillagerEntity villager, BuilderTask task,
                                               Item desired, int depth, Set<Item> visiting) {
        return ensureItemCountAvailable(world, villager, task, desired, 1, depth, visiting);
    }

    private static boolean ensureItemCountAvailable(ServerWorld world, VillagerEntity villager, BuilderTask task,
                                                    Item desired, int amount, int depth, Set<Item> visiting) {
        // Recover a previous craft/smelting output that was dropped because the 8-slot
        // villager inventory was full when the recipe completed.
        collectSpecificDrops(world, villager, villager.getBlockPos(), desired);
        int alreadyHave = countSpecificItem(villager, desired);
        if (alreadyHave >= amount) return true;
        int missing = amount - alreadyHave;
        if (depth >= 8 || !visiting.add(desired) || !VillageExpansionConfig.useRecipeBookForCrafting) {
            task.pendingMaterialItem = desired;
            task.pendingMaterialCount = amount;
            return false;
        }

        for (RecipeEntry<CraftingRecipe> entry : world.getServer().getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != desired) continue;
            int batches = (missing + output.getCount() - 1) / output.getCount();
            Map<Item, Integer> ingredients = new HashMap<>();
            for (Ingredient ingredient : entry.value().getIngredients()) {
                if (ingredient.isEmpty()) continue;
                Item input = chooseIngredientItem(world, ingredient, villager, null);
                if (input == null) {
                    task.pendingMaterialItem = desired;
                    task.pendingMaterialCount = amount;
                    return false;
                }
                ingredients.merge(input, batches, Integer::sum);
            }
            for (Map.Entry<Item, Integer> ingredient : ingredients.entrySet()) {
                if (!ensureItemCountAvailable(world, villager, task, ingredient.getKey(),
                        ingredient.getValue(), depth + 1, visiting)) return false;
            }
            for (Map.Entry<Item, Integer> ingredient : ingredients.entrySet()) {
                if (!consumeItems(villager, ingredient.getKey(), ingredient.getValue())) {
                    task.pendingMaterialItem = ingredient.getKey();
                    task.pendingMaterialCount = ingredient.getValue();
                    return false;
                }
            }
            ItemStack crafted = output.copy();
            crafted.setCount(output.getCount() * batches);
            ItemStack remainder = villager.getInventory().addStack(crafted);
            if (!remainder.isEmpty()) world.spawnEntity(new ItemEntity(world, villager.getX(), villager.getY() + 0.4D,
                    villager.getZ(), remainder));
            visiting.remove(desired);
            return countSpecificItem(villager, desired) >= amount;
        }

        // Smelting recipes (for example sand -> glass) are processed over time and consume fuel.
        for (RecipeEntry<SmeltingRecipe> entry : world.getServer().getRecipeManager().listAllOfType(RecipeType.SMELTING)) {
            ItemStack output = entry.value().getResult(world.getRegistryManager());
            if (output.isEmpty() || output.getItem() != desired) continue;
            Ingredient inputIngredient = entry.value().getIngredients().stream().filter(i -> !i.isEmpty()).findFirst().orElse(null);
            if (inputIngredient == null) break;
            Item input = chooseIngredientItem(world, inputIngredient, villager, null);
            if (input == null) break;
            if (!ensureItemCountAvailable(world, villager, task, input, 1, depth + 1, visiting)) return false;
            int cookTicks = Math.max(1, entry.value().getCookingTime());
            if (task.smeltFuelTicksRemaining < cookTicks && !consumeSmeltingFuel(villager, task, cookTicks)) {
                task.pendingMaterialItem = Items.COAL;
                task.pendingMaterialCount = 1;
                return false;
            }
            if (!consumeItems(villager, input, 1)) {
                task.pendingMaterialItem = input;
                task.pendingMaterialCount = 1;
                return false;
            }
            task.smeltFuelTicksRemaining -= cookTicks;
            task.pendingSmeltOutput = desired;
            task.smeltOutputCount = output.getCount();
            task.smeltTicksRemaining = cookTicks;
            task.resumeStage = task.stage == Stage.GATHER_MATERIAL ? Stage.BUILD : task.stage;
            task.stage = Stage.SMELT_MATERIAL;
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            world.playSound(null, villager.getBlockPos(), SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE,
                    SoundCategory.BLOCKS, 0.35F, 1.0F);
            visiting.remove(desired);
            return false;
        }

        visiting.remove(desired);
        task.pendingMaterialItem = desired;
        task.pendingMaterialCount = amount;
        return false;
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
        task.smeltTicksRemaining--;
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

    private static void tickGatherMaterial(ServerWorld world, VillagerEntity villager, BuilderTask task) {
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
        if (ensureItemCountAvailable(world, villager, task, wanted, requestedCount, 0, new HashSet<>())) {
            task.resourceSearchFailures = 0;
            if (task.deferredMaterialItem != null) {
                // A recipe dependency (for example, iron for shears) was gathered.
                // Resume the original material request instead of losing it.
                task.pendingMaterialItem = task.deferredMaterialItem;
                task.pendingMaterialCount = Math.max(1, task.deferredMaterialCount);
                task.deferredMaterialItem = null;
                task.deferredMaterialCount = 0;
                task.stage = Stage.GATHER_MATERIAL;
                task.resumeStage = null;
            } else {
                task.pendingMaterialItem = null;
                task.pendingMaterialCount = 0;
                task.stage = task.resumeStage == null ? Stage.BUILD : task.resumeStage;
                task.resumeStage = null;
            }
            task.moveTarget = null;
            task.requestedMoveTarget = null;
            return;
        }
        if (task.stage != Stage.GATHER_MATERIAL) return; // Crafting may have started a timed smelting job.
        if (task.pendingMaterialItem == null) task.pendingMaterialItem = wanted;
        if (world.getTime() < task.nextMaterialSearch) return;
        wanted = task.pendingMaterialItem;

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
                        if (task.pendingMaterialItem != null && task.pendingMaterialItem != wanted
                                && task.deferredMaterialItem == null) {
                            task.deferredMaterialItem = wanted;
                            task.deferredMaterialCount = requestedCount;
                        }
                        if (task.stage == Stage.SMELT_MATERIAL) {
                            task.resumeStage = Stage.GATHER_MATERIAL;
                        }
                        return;
                    }
                    int shearsSlot = -1;
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
                    villager.getInventory().markDirty();
                    collectSpecificDrops(world, villager, sheepPos, wanted);
                    task.nextMaterialSearch = world.getTime() + 20L;
                }
                return;
            }
        }

        int searchRadius = expandedSearchRadius(VillageExpansionConfig.materialSearchRadius,
                task.resourceSearchFailures);
        ResourceTarget raw = findDirectMaterialTarget(world, task.site, villager, wanted, searchRadius);
        if (raw != null) {
            if (moveNear(villager, task, raw.stand(), VillageExpansionConfig.navigationSpeed)) {
                BlockState state = world.getBlockState(raw.block());
                ToolKind tool = state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.getHardness(world, raw.block()) > 2.5F
                        ? ToolKind.PICKAXE : ToolKind.SHOVEL;
                equipBestTool(villager, tool);
                if (tickBreakBlock(world, villager, task, raw.block(), tool, null, wanted)) {
                    task.resourceSearchFailures = 0;
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
        for (int x = around.getX() - radius; x <= around.getX() + radius; x++) {
            for (int z = around.getZ() - radius; z <= around.getZ() + radius; z++) {
                int surfaceY = findSurfaceY(world, x, z);
                if (surfaceY == Integer.MIN_VALUE) continue;
                for (int y = Math.max(world.getBottomY() + 1, surfaceY - 12); y <= surfaceY + 2; y++) {
                    BlockPos block = new BlockPos(x, y, z);
                    if (!isLoaded(world, block)) continue;
                    BlockState state = world.getBlockState(block);
                    if (state.isAir() || state.hasBlockEntity() || state.isIn(BlockTags.LOGS)
                            || state.isIn(BlockTags.LEAVES) || !isWantedMaterialBlock(state, wanted)) continue;
                    BlockPos stand = findStandingPosition(world, block, villager);
                    if (stand == null) continue;
                    double distance = block.getSquaredDistance(villager.getBlockPos());
                    if (distance < bestDistance) { bestDistance = distance; best = new ResourceTarget(block.toImmutable(), stand); }
                }
            }
        }
        return best;
    }

    /**
     * Clay commonly generates beneath rivers and lakes. A normal surface-stand
     * lookup rejects every underwater column, so find a dry bank position within
     * mining reach and let the builder dig the clay block from the shoreline.
     */
    private static ResourceTarget findClayTarget(ServerWorld world, BlockPos around,
                                                  VillagerEntity villager, int radius) {
        ResourceTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = around.getX() - radius; x <= around.getX() + radius; x++) {
            for (int z = around.getZ() - radius; z <= around.getZ() + radius; z++) {
                int surfaceY = findSurfaceY(world, x, z);
                if (surfaceY == Integer.MIN_VALUE) continue;
                int minY = Math.max(world.getBottomY() + 1, surfaceY - 10);
                int maxY = Math.min(world.getTopY() - 2, surfaceY + 2);
                for (int y = minY; y <= maxY; y++) {
                    BlockPos clay = new BlockPos(x, y, z);
                    if (!isLoaded(world, clay) || !world.getBlockState(clay).isOf(Blocks.CLAY)) continue;
                    BlockPos stand = findDryClayWorkStand(world, clay, villager);
                    if (stand == null) continue;
                    double distance = clay.getSquaredDistance(villager.getBlockPos());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new ResourceTarget(clay.toImmutable(), stand.toImmutable());
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos findDryClayWorkStand(ServerWorld world, BlockPos clay, VillagerEntity villager) {
        BlockPos best = null;
        double bestVillagerDistance = Double.MAX_VALUE;
        // Search a wider ring than the generic adjacent-block finder so a villager
        // can work from the nearest dry river/lake bank rather than stand in water.
        for (int radius = 1; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = clay.getX() + dx;
                    int z = clay.getZ() + dz;
                    int groundY = findSurfaceY(world, x, z);
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
                    // Keep work positions within a practical shoreline reach.
                    if (stand.getSquaredDistance(clay) > 64.0D) continue;
                    double villagerDistance = stand.getSquaredDistance(villager.getBlockPos());
                    if (villagerDistance < bestVillagerDistance) {
                        bestVillagerDistance = villagerDistance;
                        best = stand.toImmutable();
                    }
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
        if (new ItemStack(wanted).isIn(ItemTags.STONE_TOOL_MATERIALS)) return true;
        String path = Registries.ITEM.getId(wanted).getPath();
        return switch (path) {
            case "cobblestone", "cobbled_deepslate", "coal", "raw_iron", "raw_copper",
                    "raw_gold", "redstone", "lapis_lazuli", "diamond", "emerald" -> true;
            default -> false;
        };
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
            default -> false;
        };
    }

    private static void tickBuild(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        if (task.buildIndex >= task.blueprint.size()) {
            completeProject(world, villager, task);
            return;
        }
        if (task.actionTimer > 0) return;

        BlockPlacement placement = task.blueprint.get(task.buildIndex);
        // Skip flowers, short/tall grass, ferns, dead bushes, vines and snow layers
        // found in a vanilla template palette. They are terrain decoration, not
        // required construction pieces, and have no place in the material budget.
        if (isPlant(placement.state)) {
            task.buildIndex++;
            task.actionTimer = 1;
            return;
        }
        BlockState existing = world.getBlockState(placement.pos);
        if (!existing.isAir() && !existing.isReplaceable()) {
            // Do not overwrite blocks placed by players or another task.
            task.buildIndex++;
            task.actionTimer = 1;
            return;
        }

        // Work from a clear position beside the current block instead of standing
        // in the middle of the footprint. This prevents the builder from placing
        // blocks through its own hitbox or through another villager/mob.
        BlockPos workStand = findConstructionStand(world, placement.pos, villager);
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

        world.setBlockState(placement.pos, state, Block.NOTIFY_ALL);
        villager.swingHand(Hand.MAIN_HAND);
        task.buildIndex++;
        task.actionTimer = VillageExpansionConfig.buildBlockDelayTicks;
    }

    private static BlockPos findConstructionStand(ServerWorld world, BlockPos target, VillagerEntity builder) {
        Box targetBox = new Box(target);
        List<BlockPos> candidates = new ArrayList<>();
        // Include the natural ground level for roof/eaves placements several
        // blocks above the floor, while also finding elevated house floors.
        int minStandY = Math.max(world.getBottomY() + 1, target.getY() - 12);
        int maxStandY = Math.min(world.getTopY() - 2, target.getY() + 2);
        for (int radius = 1; radius <= 3; radius++) {
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

                        Box entityBox = new Box(x + 0.2D, y, z + 0.2D,
                                x + 0.8D, y + 1.95D, z + 0.8D);
                        if (entityBox.intersects(targetBox)) continue;
                        boolean occupied = world.getOtherEntities(builder, entityBox).stream()
                                .anyMatch(entity -> entity.isAlive()
                                        && entity instanceof net.minecraft.entity.LivingEntity);
                        if (!occupied) candidates.add(stand.toImmutable());
                    }
                }
            }
            if (!candidates.isEmpty()) break;
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(builder.getBlockPos())))
                .orElse(null);
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
        long now = villager.getWorld().getTime();
        double distanceSquared = villager.squaredDistanceTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
        if (task.requestedMoveTarget == null || !task.requestedMoveTarget.equals(target)) {
            task.requestedMoveTarget = target.toImmutable();
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

    private static void equipForWork(VillagerEntity villager, net.minecraft.item.Item item) {
        ItemStack current = villager.getEquippedStack(EquipmentSlot.MAINHAND);
        if (!current.isOf(item)) {
            villager.equipStack(EquipmentSlot.MAINHAND, new ItemStack(item));
        }
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
        ACTIVE_TASKS.remove(villager.getUuid());
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
        ACTIVE_TASKS.remove(villager.getUuid());
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
        CLEAR_PATH("Limpando terreno"),
        LEVEL_SITE("Nivelando terreno"),
        BUILD_PATH("Construindo caminho"),
        GATHER_WOOD("Coletando madeira"),
        GATHER_DIRT("Coletando terra"),
        GATHER_STONE("Coletando pedra"),
        GATHER_MATERIAL("Coletando materiais"),
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

        private Stage stage = Stage.CLEAR_PATH;
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
        private int currentTreeLeafIndex;
        private boolean currentTreeLeavesScanned;
        private int smeltTicksRemaining;
        private int smeltFuelTicksRemaining;
        private int smeltOutputCount = 1;
        private Item pendingSmeltOutput;
        private Item pendingMaterialItem;
        private int pendingMaterialCount = 1;
        private Item deferredMaterialItem;
        private int deferredMaterialCount;
        private BlockPos saplingSearchOrigin;
        private long nextMaterialSearch;
        private BlockPos mineEntrance;
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

    private record ResourceTarget(BlockPos block, BlockPos stand) {
    }

    private record CoverBlock(BlockPos pos, BlockState originalState) {
    }

    private record MiningTarget(BlockPos rock, BlockPos stand, List<CoverBlock> cover) {
    }

    private record PathNode(BlockPos pos, double cost, double estimate) {
    }
}
