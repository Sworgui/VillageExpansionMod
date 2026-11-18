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

    /** Called by the VillagerEntity mixin: suppress vanilla Brain wandering only during work. */
    public static boolean shouldSuppressVanillaBrain(VillagerEntity villager) {
        BuilderTask task = ACTIVE_TASKS.get(villager.getUuid());
        if (task == null) return false;
        long now = villager.getWorld().getTime();
        if (villager.getWorld() instanceof ServerWorld serverWorld && nearbyDanger(serverWorld, villager)) return false;
        return !task.sleepingAtNight && now >= task.pausedUntil && !nightWithAssignedHome(villager);
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

    private static void tickTask(ServerWorld world, VillagerEntity villager, BuilderTask task) {
        long now = world.getTime();

        if (nearbyDanger(world, villager)) {
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

        if (task.actionTimer > 0) task.actionTimer--;

        switch (task.stage) {
            case CLEAR_PATH -> tickClearPath(world, villager, task);
            case LEVEL_SITE -> tickLevelSite(world, villager, task);
            case BUILD_PATH -> tickBuildPath(world, villager, task);
            case GATHER_WOOD -> tickGatherWood(world, villager, task);
            case GATHER_STONE -> tickGatherStone(world, villager, task);
            case MINE_TUNNEL -> tickMineTunnel(world, villager, task);
            case BUILD -> tickBuild(world, villager, task);
        }
    }

    private static void tryAssignBuilder(ServerWorld world, VillagerEntity villager) {
        if (!villager.isAlive() || villager.isBaby() || villager.isAiDisabled() || villager.hasCustomer()) return;
        // A villager with a real job is never converted into a builder.
        if (!villager.getVillagerData().getProfession().equals(VillagerProfession.NONE)) return;

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
        int woodUnits = (int) task.blueprint.stream().mapToInt(placement ->
                placement.state().isIn(BlockTags.LOGS) ? VillageExpansionConfig.woodUnitsPerLog : isWoodMaterialBlock(placement.state()) ? 1 : 0).sum();
        task.requiredWood = Math.max(VillageExpansionConfig.woodRequired, ((woodUnits + VillageExpansionConfig.woodUnitsPerLog - 1) / VillageExpansionConfig.woodUnitsPerLog) * VillageExpansionConfig.woodUnitsPerLog);
        task.requiredStone = Math.max(VillageExpansionConfig.stoneRequired, (int) task.blueprint.stream()
                .filter(placement -> isStoneMaterialBlock(placement.state())).count());
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
        nbt.putString("originalHandItem", Registries.ITEM.getId(task.originalMainHand.getItem()).toString());
        nbt.putInt("originalHandCount", task.originalMainHand.getCount());
        nbt.putInt("originalHandDamage", task.originalMainHand.getDamage());
        writePositionList(nbt, "treeTargets", task.treeTargets);
        writePositionList(nbt, "pathBlocks", task.pathBlocks);
        writePositionList(nbt, "currentTreeLogs", task.currentTreeLogs);
        writeLevelOperations(nbt, task.levelOperations);
        nbt.putInt("currentTreeLogIndex", task.currentTreeLogIndex);
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
            task.treeIndex = nbt.getInt("treeIndex");
            task.levelIndex = nbt.getInt("levelIndex");
            task.pathIndex = nbt.getInt("pathIndex");
            task.buildIndex = nbt.getInt("buildIndex");
            task.wood = nbt.getInt("wood");
            task.stone = nbt.getInt("stone");
            task.requiredWood = nbt.contains("requiredWood") ? nbt.getInt("requiredWood") : VillageExpansionConfig.woodRequired;
            task.requiredStone = nbt.contains("requiredStone") ? nbt.getInt("requiredStone") : VillageExpansionConfig.stoneRequired;
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
            if (nbt.contains("currentTreeBase", NbtElement.LONG_TYPE)) {
                task.currentTreeBase = BlockPos.fromLong(nbt.getLong("currentTreeBase"));
            }
            task.treeTargets.addAll(readPositionList(nbt, "treeTargets"));
            task.pathBlocks.addAll(readPositionList(nbt, "pathBlocks"));
            task.currentTreeLogs.addAll(readPositionList(nbt, "currentTreeLogs"));
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
            int woodUnits = (int) task.blueprint.stream().mapToInt(placement ->
                    placement.state().isIn(BlockTags.LOGS) ? VillageExpansionConfig.woodUnitsPerLog : isWoodMaterialBlock(placement.state()) ? 1 : 0).sum();
            task.requiredWood = Math.max(task.requiredWood, ((woodUnits + VillageExpansionConfig.woodUnitsPerLog - 1) / VillageExpansionConfig.woodUnitsPerLog) * VillageExpansionConfig.woodUnitsPerLog);
            task.requiredStone = Math.max(task.requiredStone, (int) task.blueprint.stream()
                    .filter(placement -> isStoneMaterialBlock(placement.state())).count());
            BlockPos pathGoal = site.add(0, 0, 3);
            if (task.templateId != null) {
                Optional<HouseTemplate> restoredTemplate = VillageTemplateLibrary.load(world, Identifier.tryParse(task.templateId));
                if (restoredTemplate.isPresent()) pathGoal = restoredTemplate.get().pathGoal(site);
            }
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
                if (villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME).isEmpty()) {
                    everyoneHasHome = false;
                    break;
                }
            }
            if (foundVillager && everyoneHasHome) saved.markVillageFounded(villageId);
        }
    }

    private static void buildMeetingPointIfNeeded(ServerWorld world, VillageExpansionState saved,
                                                  VillageExpansionState.VillageRecord village) {
        if (village == null || village.hasBell()) return;
        Optional<HouseTemplate> meeting = VillageTemplateLibrary.chooseMeetingPoint(world, village.center());
        if (meeting.isPresent() && meeting.get().bellOffset() != null) {
            HouseTemplate template = meeting.get();
            BlockPos origin = village.center();
            for (VillageTemplateLibrary.TemplateBlock block : template.blocks()) {
                BlockPos target = origin.add(block.offset());
                if (!isLoaded(world, target)) continue;
                BlockState current = world.getBlockState(target);
                if (current.isAir() || current.isReplaceable()) {
                    world.setBlockState(target, block.state(), Block.NOTIFY_ALL);
                }
            }
            BlockPos bell = origin.add(template.bellOffset());
            if (world.getBlockState(bell).isOf(Blocks.BELL)) {
                saved.registerBell(village.id(), bell);
                return;
            }
        }
        int surfaceY = findSurfaceY(world, village.center().getX(), village.center().getZ());
        if (surfaceY != Integer.MIN_VALUE) {
            BlockPos support = new BlockPos(village.center().getX(), surfaceY, village.center().getZ());
            BlockPos bell = support.up();
            if (world.getBlockState(bell).isAir() && world.getBlockState(support).isSolidBlock(world, support)) {
                world.setBlockState(bell, Blocks.BELL.getDefaultState(), Block.NOTIFY_ALL);
                saved.registerBell(village.id(), bell);
            }
        }
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
        if (tickCutTree(world, villager, task, treeBase)) {
            task.wood = countInventoryItems(villager, ItemTags.LOGS) * VillageExpansionConfig.woodUnitsPerLog;
            task.treeIndex++;
            task.moveTarget = null;
        }
    }

    /** Breaks every log in a tree individually; no instant tree felling. */
    private static boolean tickCutTree(ServerWorld world, VillagerEntity villager, BuilderTask task, BlockPos base) {
        if (task.currentTreeBase == null || !task.currentTreeBase.equals(base)) {
            clearBreakingProgress(world, villager, task);
            task.currentTreeBase = base.toImmutable();
            task.currentTreeLogs.clear();
            task.currentTreeLogIndex = 0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 16; dy++) {
                        BlockPos pos = base.add(dx, dy, dz);
                        if (isLoaded(world, pos) && world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                            task.currentTreeLogs.add(pos.toImmutable());
                        }
                    }
                }
            }
            task.currentTreeLogs.sort(Comparator.comparingInt(BlockPos::getY)
                    .thenComparingDouble(pos -> pos.getSquaredDistance(base)));
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
            return false;
        }

        // Leaves are not construction materials; remove them after all trunk blocks are harvested.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 16; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = base.add(dx, dy, dz);
                    if (world.getBlockState(pos).isIn(BlockTags.LEAVES)) world.breakBlock(pos, false, villager);
                }
            }
        }
        task.currentTreeBase = null;
        task.currentTreeLogs.clear();
        task.currentTreeLogIndex = 0;
        clearBreakingProgress(world, villager, task);
        return true;
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
        boolean broken = world.breakBlock(pos, true, villager);
        if (broken) {
            collectDrops(world, villager, pos, acceptedDrops);
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
        BlockPos workStand = new BlockPos(task.site.getX(), task.site.getY() + 1, task.site.getZ());
        if (!moveNear(villager, task, workStand, VillageExpansionConfig.navigationSpeed)) {
            return;
        }

        if (!task.plotVegetationCleared) {
            clearPlotVegetation(world, villager, task.site, task.footprintX, task.footprintZ);
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
                if (!moveNear(villager, task, treeStand, VillageExpansionConfig.navigationSpeed)) {
                    return;
                }
                if (tickCutTree(world, villager, task, treeBase)) {
                    task.wood = countInventoryItems(villager, ItemTags.LOGS) * VillageExpansionConfig.woodUnitsPerLog;
                    task.moveTarget = null;
                }
                return;
            }
            if (!obstacle.isAir()) {
                task.pathIndex++;
                task.moveTarget = null;
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
        task.actionTimer = VillageExpansionConfig.buildBlockDelayTicks;
    }

    private static void clearPlotVegetation(ServerWorld world, VillagerEntity villager, BlockPos site,
                                             int sizeX, int sizeZ) {
        for (int x = site.getX(); x < site.getX() + sizeX; x++) {
            for (int z = site.getZ(); z < site.getZ() + sizeZ; z++) {
                for (int y = site.getY() + 1; y <= site.getY() + 10; y++) {
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
        if (task.wood >= task.requiredWood) {
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
        if (!moveNear(villager, task, target.stand, VillageExpansionConfig.navigationSpeed)) return;
        if (tickCutTree(world, villager, task, target.block)) {
            task.wood = countInventoryItems(villager, ItemTags.LOGS) * VillageExpansionConfig.woodUnitsPerLog;
            task.currentResource = null;
            task.moveTarget = null;
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
        ResourceTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = VillageExpansionConfig.woodSearchRadius;

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
                    if (isWoodTargetReserved(world, pos, villager.getUuid())) {
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
        task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
        if (task.stone >= task.requiredStone) {
            villager.getNavigation().stop();
            task.moveTarget = null;
            task.stage = Stage.BUILD;
            return;
        }

        if (task.currentMine == null) {
            if (world.getTime() < task.nextResourceSearch) return;
            equipBestTool(villager, ToolKind.PICKAXE);
            BlockPos searchFrom = task.mineFace == null ? task.site : task.mineFace;
            MiningTarget exposed = findExposedCaveStone(world, searchFrom, villager);
            if (exposed != null) {
                task.currentMine = exposed;
                task.moveTarget = null;
            } else {
                task.stage = Stage.MINE_TUNNEL;
                task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
                task.moveTarget = null;
                return;
            }
        }

        MiningTarget mine = task.currentMine;
        if (!moveNear(villager, task, mine.stand(), VillageExpansionConfig.navigationSpeed)) return;
        BlockState rock = world.getBlockState(mine.rock());
        if (!isMineableStoneOrOre(rock, villager)) {
            task.currentMine = null;
            task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
            return;
        }
        equipBestTool(villager, ToolKind.PICKAXE);
        if (!tickBreakBlock(world, villager, task, mine.rock(), ToolKind.PICKAXE, null)) return;
        task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
        task.currentMine = null;
        task.moveTarget = null;
        task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
    }

    /** Searches exposed stone/ore along safe, dry cave floor cells before digging. */
    private static MiningTarget findExposedCaveStone(ServerWorld world, BlockPos around, VillagerEntity villager) {
        int radius = VillageExpansionConfig.caveSearchRadius;
        int minY = Math.max(world.getBottomY() + 2, around.getY() - VillageExpansionConfig.caveSearchDepth);
        int maxY = Math.min(world.getTopY() - 3, around.getY() + 4);
        MiningTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = around.getX() - radius; x <= around.getX() + radius; x++) {
            for (int z = around.getZ() - radius; z <= around.getZ() + radius; z++) {
                if (!world.isChunkLoaded(new ChunkPos(x >> 4, z >> 4).toLong())) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos stand = new BlockPos(x, y, z);
                    if (!world.getBlockState(stand).isAir() || !world.getBlockState(stand.up()).isAir()
                            || !world.getFluidState(stand).isEmpty() || !world.getFluidState(stand.up()).isEmpty()
                            || !world.getBlockState(stand.down()).isSolidBlock(world, stand.down())
                            || !isCaveLikeAirCell(world, stand)) continue;
                    for (Direction direction : Direction.Type.HORIZONTAL) {
                        BlockPos rockPos = stand.offset(direction);
                        if (!isLoaded(world, rockPos) || !isMineableStoneOrOre(world.getBlockState(rockPos), villager)) continue;
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
        task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
        if (task.stone >= task.requiredStone) {
            task.stage = Stage.BUILD;
            villager.getNavigation().stop();
            return;
        }
        VillageExpansionState saved = world.getPersistentStateManager().getOrCreate(
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        VillageExpansionState.VillageRecord village = saved.getVillage(task.villageId);
        if (task.mineEntrance == null || task.mineFace == null || task.mineDirection == null) {
            BlockPos villageCenter = village == null ? task.site : village.center();
            BlockPos entrance = village == null ? null : village.mines().stream()
                    .filter(pos -> !isMineReserved(world, pos, villager.getUuid()))
                    .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(villager.getBlockPos())))
                    .orElse(null);
            if (entrance == null) {
                entrance = findMineEntrance(world, task.site, villageCenter,
                        village == null ? List.of() : village.mines());
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
            return;
        }
        if (!world.getBlockState(feet).isAir()) {
            if (!tickBreakBlock(world, villager, task, feet, ToolKind.PICKAXE, null)) return;
            return;
        }
        if (!world.getBlockState(head).isAir()) {
            if (!tickBreakBlock(world, villager, task, head, ToolKind.PICKAXE, null)) return;
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
        if (task.mineSteps % VillageExpansionConfig.mineSupportInterval == 0) {
            addMineSupport(world, villager, task.mineFace, task.mineDirection);
        }

        // A breakthrough is detected by the cave scan, not by filling the tunnel back in.
        if (task.mineSteps % 3 == 0 && world.getTime() >= task.nextResourceSearch) {
            MiningTarget cave = findExposedCaveStone(world, task.mineFace, villager);
            task.nextResourceSearch = world.getTime() + VillageExpansionConfig.caveSearchIntervalTicks;
            if (cave != null) {
                task.currentMine = cave;
                task.stage = Stage.GATHER_STONE;
            }
        }
        if (task.stone >= task.requiredStone) task.stage = Stage.BUILD;
    }

    private static int countMineSteps(BlockPos start, BlockPos face, Direction direction) {
        int horizontal = direction.getAxis() == Direction.Axis.X
                ? Math.abs(face.getX() - start.getX()) : Math.abs(face.getZ() - start.getZ());
        return Math.max(horizontal, Math.abs(face.getY() - start.getY()));
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
                                             List<BlockPos> knownMines) {
        for (int radius = 20; radius <= 36; radius += 4) {
            for (int i = 0; i < 32; i++) {
                double angle = i * (Math.PI * 2.0D / 32.0D);
                int x = villageCenter.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = villageCenter.getZ() + (int) Math.round(Math.sin(angle) * radius);
                int y = findSurfaceY(world, x, z);
                if (y == Integer.MIN_VALUE) continue;
                BlockPos ground = new BlockPos(x, y, z);
                if (!isLoaded(world, ground) || !isTerrain(world.getBlockState(ground))) continue;
                if (Math.abs(y - site.getY()) > 6) continue;
                if (knownMines.stream().anyMatch(mine -> mine.getSquaredDistance(ground) < 16.0D * 16.0D)) continue;
                return ground.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos villageCenterOrSite(VillageExpansionState.VillageRecord village, BlockPos site) {
        return village == null ? site : village.center();
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
        BlockPos entrance = findMineEntrance(world, task.site, villageCenter, known);
        if (entrance == null) return false;
        task.mineEntrance = entrance;
        task.mineDirection = mineDirectionAwayFrom(villageCenter, entrance);
        task.mineFace = findExistingMineFront(world, entrance.up(), task.mineDirection);
        task.mineSteps = countMineSteps(entrance.up(), task.mineFace, task.mineDirection);
        task.moveTarget = null;
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
        BlockPos workStand = task.site.add(task.footprintX / 2, 1, task.footprintZ / 2);
        if (!moveNear(villager, task, workStand, VillageExpansionConfig.navigationSpeed)) return;
        if (task.buildIndex >= task.blueprint.size()) {
            completeProject(world, villager, task);
            return;
        }
        if (task.actionTimer > 0) return;

        BlockPlacement placement = task.blueprint.get(task.buildIndex);
        if (!world.getBlockState(placement.pos).isAir()
                && !world.getBlockState(placement.pos).isReplaceable()) {
            // Do not overwrite blocks placed by players or another task.
            task.buildIndex++;
            task.actionTimer = 1;
            return;
        }

        BlockState state = placement.state;
        if (isStoneMaterialBlock(state)) {
            if (!consumeOneTaggedItem(villager, ItemTags.STONE_TOOL_MATERIALS)) {
                task.stage = Stage.GATHER_STONE;
                task.stone = countInventoryItems(villager, ItemTags.STONE_TOOL_MATERIALS);
                task.moveTarget = null;
                return;
            }
        } else if (isWoodMaterialBlock(state)) {
            boolean directLog = state.isIn(BlockTags.LOGS);
            if (directLog || task.woodPiecesPlaced % VillageExpansionConfig.woodUnitsPerLog == 0) {
                if (!consumeOneTaggedItem(villager, ItemTags.LOGS)) {
                    task.stage = Stage.GATHER_WOOD;
                    task.wood = countInventoryItems(villager, ItemTags.LOGS) * VillageExpansionConfig.woodUnitsPerLog;
                    task.moveTarget = null;
                    return;
                }
            }
            if (!directLog) task.woodPiecesPlaced++;
        }

        world.setBlockState(placement.pos, state, Block.NOTIFY_ALL);
        villager.swingHand(Hand.MAIN_HAND);
        task.buildIndex++;
        task.actionTimer = VillageExpansionConfig.buildBlockDelayTicks;
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
                VillageExpansionState.TYPE, VillageExpansionState.STORAGE_ID);
        VillageExpansionState.VillageRecord village = saved.getVillage(task.villageId);
        if (village != null) {
            village.registerHouse(task.site, task.professional);
            if (village.isFounding() && task.professional && task.foundingProject) {
                buildMeetingPointIfNeeded(world, saved, village);
            }
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
        villager.equipStack(EquipmentSlot.MAINHAND, task.originalMainHand.copy());
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                task.site.getX() + 0.5D, task.site.getY() + 4.0D, task.site.getZ() + 0.5D,
                8, 1.2D, 0.8D, 1.2D, 0.05D);
        world.playSound(null, task.site, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.9F, 1.0F);

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
        CLEAR_PATH,
        LEVEL_SITE,
        BUILD_PATH,
        GATHER_WOOD,
        GATHER_STONE,
        MINE_TUNNEL,
        BUILD
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
        private int woodPiecesPlaced;
        private int actionTimer;
        private long nextResourceSearch;
        private BlockPos moveTarget;
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
