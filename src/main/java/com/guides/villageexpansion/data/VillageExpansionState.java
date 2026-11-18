package com.guides.villageexpansion.data;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistent per-dimension record of generated villages, houses, mines and
 * unfinished worker tasks. Active task snapshots allow work to resume after reload.
 */
public final class VillageExpansionState extends PersistentState {
    public static final String STORAGE_ID = "village_expansion_data";

    public static final Type<VillageExpansionState> TYPE = new Type<>(
            VillageExpansionState::new,
            VillageExpansionState::fromNbt,
            DataFixTypes.LEVEL
    );

    private final List<VillageRecord> villages = new ArrayList<>();
    private long nextVillageId = 1L;
    private final Map<UUID, NbtCompound> activeTasks = new HashMap<>();

    public static VillageExpansionState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        VillageExpansionState state = new VillageExpansionState();
        state.nextVillageId = Math.max(1L, nbt.getLong("nextVillageId"));

        NbtList villageList = nbt.getList("villages", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < villageList.size(); i++) {
            NbtCompound villageNbt = villageList.getCompound(i);
            long id = villageNbt.getLong("id");
            BlockPos center = BlockPos.fromLong(villageNbt.getLong("center"));
            int common = Math.max(0, villageNbt.getInt("commonHouses"));
            int professional = Math.max(0, villageNbt.getInt("professionalHouses"));

            List<BlockPos> houses = new ArrayList<>();
            NbtList houseList = villageNbt.getList("houses", NbtElement.LONG_TYPE);
            for (int h = 0; h < houseList.size(); h++) {
                houses.add(BlockPos.fromLong(((NbtLong) houseList.get(h)).longValue()));
            }

            boolean founding = villageNbt.getBoolean("founding");
            UUID founderUuid = null;
            String founderText = villageNbt.getString("founderUuid");
            if (!founderText.isBlank()) {
                try {
                    founderUuid = UUID.fromString(founderText);
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed legacy/custom data and leave the village available for recovery.
                }
            }

            List<BlockPos> mines = new ArrayList<>();
            NbtList mineList = villageNbt.getList("mines", NbtElement.LONG_TYPE);
            for (int m = 0; m < mineList.size(); m++) {
                mines.add(BlockPos.fromLong(((NbtLong) mineList.get(m)).longValue()));
            }
            boolean bellBuilt = villageNbt.getBoolean("bellBuilt");
            BlockPos bellPos = villageNbt.contains("bellPos", NbtElement.LONG_TYPE)
                    ? BlockPos.fromLong(villageNbt.getLong("bellPos")) : null;
            state.villages.add(new VillageRecord(id, center, common, professional, houses, mines,
                    founding, founderUuid, bellBuilt, bellPos));
            state.nextVillageId = Math.max(state.nextVillageId, id + 1L);
        }
        NbtList taskList = nbt.getList("activeTasks", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < taskList.size(); i++) {
            NbtCompound taskNbt = taskList.getCompound(i);
            try {
                UUID villagerId = UUID.fromString(taskNbt.getString("villager"));
                NbtCompound data = taskNbt.getCompound("data");
                state.activeTasks.put(villagerId, data.copy());
            } catch (IllegalArgumentException ignored) {
                // Ignore an invalid task entry; its villager can be reassigned normally.
            }
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        nbt.putLong("nextVillageId", nextVillageId);
        NbtList villageList = new NbtList();

        for (VillageRecord village : villages) {
            NbtCompound villageNbt = new NbtCompound();
            villageNbt.putLong("id", village.id);
            villageNbt.putLong("center", village.center.asLong());
            villageNbt.putInt("commonHouses", village.commonHouses);
            villageNbt.putInt("professionalHouses", village.professionalHouses);
            villageNbt.putBoolean("founding", village.founding);
            villageNbt.putString("founderUuid", village.founderUuid == null ? "" : village.founderUuid.toString());
            villageNbt.putBoolean("bellBuilt", village.bellBuilt);
            if (village.bellPos != null) villageNbt.putLong("bellPos", village.bellPos.asLong());

            NbtList mineList = new NbtList();
            for (BlockPos mine : village.mines) mineList.add(NbtLong.of(mine.asLong()));
            villageNbt.put("mines", mineList);

            NbtList houseList = new NbtList();
            for (BlockPos house : village.houses) {
                houseList.add(net.minecraft.nbt.NbtLong.of(house.asLong()));
            }
            villageNbt.put("houses", houseList);
            villageList.add(villageNbt);
        }

        nbt.put("villages", villageList);
        NbtList taskList = new NbtList();
        for (Map.Entry<UUID, NbtCompound> entry : activeTasks.entrySet()) {
            NbtCompound item = new NbtCompound();
            item.putString("villager", entry.getKey().toString());
            item.put("data", entry.getValue().copy());
            taskList.add(item);
        }
        nbt.put("activeTasks", taskList);
        return nbt;
    }

    public VillageRecord findOrCreateVillage(BlockPos anchor) {
        VillageRecord nearest = villages.stream()
                .filter(v -> v.center.getSquaredDistance(anchor) <= 72L * 72L)
                .min(Comparator.comparingDouble(v -> v.center.getSquaredDistance(anchor)))
                .orElse(null);

        if (nearest != null) {
            return nearest;
        }

        VillageRecord created = new VillageRecord(nextVillageId++, anchor.toImmutable(), 0, 0,
                new ArrayList<>(), new ArrayList<>(), false, null, false, null);
        villages.add(created);
        markDirty();
        return created;
    }

    /** Finds a known settlement near a position, including one that is still being founded. */
    public VillageRecord findNearbyVillage(BlockPos position, double maxDistance) {
        double maxDistanceSquared = maxDistance * maxDistance;
        return villages.stream()
                .filter(v -> v.center.getSquaredDistance(position) <= maxDistanceSquared)
                .min(Comparator.comparingDouble(v -> v.center.getSquaredDistance(position)))
                .orElse(null);
    }

    /** Creates a new settlement and reserves its two-step founding sequence for one villager. */
    public VillageRecord createFoundingVillage(BlockPos anchor, UUID founderUuid) {
        VillageRecord nearby = findNearbyVillage(anchor, 72.0D);
        if (nearby != null) {
            return nearby;
        }

        VillageRecord created = new VillageRecord(nextVillageId++, anchor.toImmutable(), 0, 0,
                new ArrayList<>(), new ArrayList<>(), true, founderUuid, false, null);
        villages.add(created);
        markDirty();
        return created;
    }

    /** Returns the unfinished founding project owned by this villager, if any. */
    public VillageRecord findFoundingVillageForFounder(UUID founderUuid) {
        for (VillageRecord village : villages) {
            if (village.founding && founderUuid.equals(village.founderUuid)) {
                return village;
            }
        }
        return null;
    }

    public void saveActiveTask(UUID villagerId, NbtCompound taskData) {
        activeTasks.put(villagerId, taskData.copy());
        markDirty();
    }

    public NbtCompound getActiveTask(UUID villagerId) {
        NbtCompound data = activeTasks.get(villagerId);
        return data == null ? null : data.copy();
    }

    public void removeActiveTask(UUID villagerId) {
        if (activeTasks.remove(villagerId) != null) markDirty();
    }

    /** Counts persisted projects, including workers whose entity chunks are currently unloaded. */
    public int getActiveTaskCountForVillage(long villageId) {
        int count = 0;
        for (NbtCompound task : activeTasks.values()) {
            if (task.getLong("village") == villageId) count++;
        }
        return count;
    }

    /** Returns saved construction plots so another worker cannot reserve the same site after reload. */
    public List<BlockPos> getActiveTaskSitesForVillage(long villageId) {
        List<BlockPos> sites = new ArrayList<>();
        for (NbtCompound task : activeTasks.values()) {
            if (task.getLong("village") == villageId && task.contains("site", NbtElement.LONG_TYPE)) {
                sites.add(BlockPos.fromLong(task.getLong("site")));
            }
        }
        return sites;
    }

    /** Returns all saved mine entrances in this dimension, including unloaded workers' reservations. */
    public List<BlockPos> getActiveTaskMineEntrances(UUID excludedVillager) {
        List<BlockPos> entrances = new ArrayList<>();
        for (Map.Entry<UUID, NbtCompound> entry : activeTasks.entrySet()) {
            if (entry.getKey().equals(excludedVillager)) continue;
            NbtCompound task = entry.getValue();
            if (task.contains("mineEntrance", NbtElement.LONG_TYPE)) {
                entrances.add(BlockPos.fromLong(task.getLong("mineEntrance")));
            }
        }
        return entrances;
    }

    public List<BlockPos> getActiveTaskResourceBlocks(UUID excludedVillager) {
        return getTaskPositions("resourceBlock", excludedVillager);
    }

    public List<BlockPos> getActiveTaskMiningBlocks(UUID excludedVillager) {
        return getTaskPositions("currentMineRock", excludedVillager);
    }

    private List<BlockPos> getTaskPositions(String key, UUID excludedVillager) {
        List<BlockPos> positions = new ArrayList<>();
        for (Map.Entry<UUID, NbtCompound> entry : activeTasks.entrySet()) {
            if (entry.getKey().equals(excludedVillager)) continue;
            NbtCompound task = entry.getValue();
            if (task.contains(key, NbtElement.LONG_TYPE)) positions.add(BlockPos.fromLong(task.getLong(key)));
        }
        return positions;
    }

    public void registerMine(long villageId, BlockPos entrance) {
        VillageRecord village = getVillage(villageId);
        if (village != null && village.addMine(entrance)) markDirty();
    }

    public void registerBell(long villageId, BlockPos bellPos) {
        VillageRecord village = getVillage(villageId);
        if (village != null) {
            village.setBell(bellPos);
            markDirty();
        }
    }

    public void markVillageFounded(long villageId) {
        VillageRecord village = getVillage(villageId);
        if (village != null && village.founding) {
            village.founding = false;
            village.founderUuid = null;
            markDirty();
        }
    }

    public List<Long> getFoundingVillageIds() {
        List<Long> ids = new ArrayList<>();
        for (VillageRecord village : villages) if (village.founding) ids.add(village.id);
        return ids;
    }

    public VillageRecord getVillage(long id) {
        for (VillageRecord village : villages) {
            if (village.id == id) {
                return village;
            }
        }
        return null;
    }

    public static final class VillageRecord {
        private final long id;
        private final BlockPos center;
        private int commonHouses;
        private int professionalHouses;
        private final List<BlockPos> houses;
        private final List<BlockPos> mines;
        private boolean founding;
        private UUID founderUuid;
        private boolean bellBuilt;
        private BlockPos bellPos;

        private VillageRecord(long id, BlockPos center, int commonHouses, int professionalHouses,
                              List<BlockPos> houses, List<BlockPos> mines, boolean founding,
                              UUID founderUuid, boolean bellBuilt, BlockPos bellPos) {
            this.id = id;
            this.center = center.toImmutable();
            this.commonHouses = commonHouses;
            this.professionalHouses = professionalHouses;
            this.houses = houses;
            this.mines = mines;
            this.founding = founding;
            this.founderUuid = founderUuid;
            this.bellBuilt = bellBuilt;
            this.bellPos = bellPos == null ? null : bellPos.toImmutable();
        }

        public long id() {
            return id;
        }

        public BlockPos center() {
            return center;
        }

        /**
         * Normal policy: two ordinary homes before each job-site home.
         * New settlements have a special C, P bootstrap sequence, then use
         * the normal pattern (C, P, C, C, P, ...).
         */
        public boolean nextHouseIsProfessional() {
            // A brand-new settlement starts with one ordinary home and then
            // one matching job-site house before normal 2:1 expansion begins.
            if (founding) {
                return commonHouses >= 1 && professionalHouses == 0;
            }
            return commonHouses >= (professionalHouses + 1) * 2;
        }

        public boolean isFounding() {
            return founding;
        }

        public boolean isFounder(UUID uuid) {
            return founderUuid != null && founderUuid.equals(uuid);
        }

        public boolean needsFoundingProfessional() {
            return founding && commonHouses >= 1 && professionalHouses == 0;
        }

        public List<BlockPos> mines() {
            return List.copyOf(mines);
        }

        public boolean isNearMine(BlockPos candidate, double minDistance) {
            double distanceSquared = minDistance * minDistance;
            for (BlockPos mine : mines) {
                if (mine.getSquaredDistance(candidate) < distanceSquared) return true;
            }
            return false;
        }

        private boolean addMine(BlockPos entrance) {
            if (mines.stream().anyMatch(existing -> existing.getSquaredDistance(entrance) < 8L * 8L)) return false;
            mines.add(entrance.toImmutable());
            return true;
        }

        private void setBell(BlockPos pos) {
            bellBuilt = true;
            bellPos = pos.toImmutable();
        }

        public boolean hasBell() { return bellBuilt && bellPos != null; }
        public BlockPos bellPos() { return bellPos; }

        public boolean isNearExistingHouse(BlockPos candidate, double minDistance) {
            double minDistanceSquared = minDistance * minDistance;
            for (BlockPos house : houses) {
                if (house.getSquaredDistance(candidate) < minDistanceSquared) {
                    return true;
                }
            }
            return false;
        }

        public void registerHouse(BlockPos pos, boolean professional) {
            if (houses.stream().anyMatch(existing -> existing.getSquaredDistance(pos) < 9L * 9L)) {
                return;
            }

            houses.add(pos.toImmutable());
            if (professional) {
                professionalHouses++;
            } else {
                commonHouses++;
            }

            // Founding is finalized separately only after a meeting bell exists
            // and every villager in the settlement has a HOME memory.
        }

        public int commonHouses() {
            return commonHouses;
        }

        public int professionalHouses() {
            return professionalHouses;
        }

        public int totalHouses() {
            return commonHouses + professionalHouses;
        }
    }
}
