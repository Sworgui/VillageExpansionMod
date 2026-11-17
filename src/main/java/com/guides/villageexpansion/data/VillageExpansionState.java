package com.guides.villageexpansion.data;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Persistent per-dimension record of generated villages and houses.
 * Unfinished worker tasks are deliberately runtime-only: if the server stops
 * mid-project, the villager can be assigned again after the next load.
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
                houses.add(BlockPos.fromLong(houseList.getLong(h)));
            }

            state.villages.add(new VillageRecord(id, center, common, professional, houses));
            state.nextVillageId = Math.max(state.nextVillageId, id + 1L);
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

            NbtList houseList = new NbtList();
            for (BlockPos house : village.houses) {
                houseList.add(net.minecraft.nbt.NbtLong.of(house.asLong()));
            }
            villageNbt.put("houses", houseList);
            villageList.add(villageNbt);
        }

        nbt.put("villages", villageList);
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

        VillageRecord created = new VillageRecord(nextVillageId++, anchor.toImmutable(), 0, 0, new ArrayList<>());
        villages.add(created);
        markDirty();
        return created;
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

        private VillageRecord(long id, BlockPos center, int commonHouses, int professionalHouses, List<BlockPos> houses) {
            this.id = id;
            this.center = center.toImmutable();
            this.commonHouses = commonHouses;
            this.professionalHouses = professionalHouses;
            this.houses = houses;
        }

        public long id() {
            return id;
        }

        public BlockPos center() {
            return center;
        }

        /**
         * Policy: two ordinary homes are completed before the next job-site home.
         * Example: C, C, P, C, C, P...
         */
        public boolean nextHouseIsProfessional() {
            return commonHouses >= (professionalHouses + 1) * 2;
        }

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
