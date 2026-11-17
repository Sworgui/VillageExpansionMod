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

            state.villages.add(new VillageRecord(id, center, common, professional, houses, founding, founderUuid));
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
            villageNbt.putBoolean("founding", village.founding);
            villageNbt.putString("founderUuid", village.founderUuid == null ? "" : village.founderUuid.toString());

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

        VillageRecord created = new VillageRecord(nextVillageId++, anchor.toImmutable(), 0, 0,
                new ArrayList<>(), false, null);
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
                new ArrayList<>(), true, founderUuid);
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
        private boolean founding;
        private UUID founderUuid;

        private VillageRecord(long id, BlockPos center, int commonHouses, int professionalHouses,
                              List<BlockPos> houses, boolean founding, UUID founderUuid) {
            this.id = id;
            this.center = center.toImmutable();
            this.commonHouses = commonHouses;
            this.professionalHouses = professionalHouses;
            this.houses = houses;
            this.founding = founding;
            this.founderUuid = founderUuid;
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

            // The starter village is considered founded only after both its
            // first home and its first workstation house have been completed.
            if (founding && commonHouses >= 1 && professionalHouses >= 1) {
                founding = false;
                founderUuid = null;
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
