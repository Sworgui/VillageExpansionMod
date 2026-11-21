package com.guides.villageexpansion.structures;

import com.guides.villageexpansion.mixin.StructureTemplateAccessor;
import com.guides.villageexpansion.compat.ProfessionCompatibilityRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.state.property.Property;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** Loads Mojang's own village templates from the active Minecraft data pack. */
public final class VillageTemplateLibrary {
    private VillageTemplateLibrary() {}

    public static Optional<HouseTemplate> chooseHouse(ServerWorld world, BlockPos near,
                                                       boolean professional, boolean prioritizeSmith) {
        String style = villageStyle(world, near);
        if (professional) {
            Optional<HouseTemplate> modded = ProfessionCompatibilityRegistry.chooseHouse(world, near, style);
            if (modded.isPresent()) return modded;
        }
        String base = "village/" + style + "/houses/";
        List<String> candidates = new ArrayList<>();

        if (professional && prioritizeSmith) {
            // The vanilla structure names differ slightly between village styles.
            // These candidates are the actual 1.21.1 data-pack identifiers.
            switch (style) {
                case "desert" -> candidates.addAll(List.of(
                        "desert_tool_smith_1", "desert_weaponsmith_1", "desert_armorer_1"));
                case "savanna" -> candidates.addAll(List.of(
                        "savanna_tool_smith_1", "savanna_weaponsmith_1", "savanna_weaponsmith_2",
                        "savanna_armorer_1"));
                case "taiga" -> candidates.addAll(List.of(
                        "taiga_tool_smith_1", "taiga_weaponsmith_1", "taiga_weaponsmith_2",
                        "taiga_armorer_house_1", "taiga_armorer_house_2", "taiga_armorer_2"));
                case "snowy" -> candidates.addAll(List.of(
                        "snowy_tool_smith_1", "snowy_weapon_smith_1",
                        "snowy_armorer_house_1", "snowy_armorer_house_2"));
                default -> candidates.addAll(List.of(
                        "plains_tool_smith_1", "plains_weaponsmith_1", "plains_armorer_house_1"));
            }
        } else if (professional) {
            // Alternate profession buildings. Several styles use different
            // suffixes (for example mason_1 vs masons_house_1).
            candidates.addAll(List.of(
                    style + "_fletcher_house_1",
                    style + "_library_1", style + "_library_2",
                    style + "_cartographer_house_1", style + "_cartographer_1",
                    style + "_butcher_shop_1", style + "_butchers_shop_1",
                    style + "_fisher_cottage_1", style + "_fisher_1",
                    style + "_masons_house_1", style + "_mason_1",
                    style + "_shepherds_house_1", style + "_shepherd_house_1", style + "_shepherd_1",
                    style + "_tannery_1"
            ));
        }

        // Small house counts vary by style; trying 1..8 is safe because missing
        // templates are simply skipped by StructureTemplateManager.
        List<Integer> order = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));
        java.util.Collections.shuffle(order, new Random(world.getTime() + near.asLong()));
        for (int index : order) candidates.add(style + "_small_house_" + index);
        for (int index = 1; index <= 4; index++) candidates.add(style + "_medium_house_" + index);

        for (String name : candidates) {
            Optional<HouseTemplate> found = load(world, Identifier.of("minecraft", base + name), near);
            if (found.isPresent() && found.get().hasUsefulHomeFeatures()
                    && (!professional || found.get().workstationOffset() != null)) {
                return found;
            }
        }
        return Optional.empty();
    }

    public static Optional<HouseTemplate> chooseMeetingPoint(ServerWorld world, BlockPos near) {
        String style = villageStyle(world, near);
        for (int variant = 1; variant <= 4; variant++) {
            String path = "village/" + style + "/town_centers/" + style + "_meeting_point_" + variant;
            Optional<HouseTemplate> found = load(world, Identifier.of("minecraft", path));
            if (found.isPresent() && found.get().bellOffset() != null) return found;
        }
        for (int variant = 1; variant <= 3; variant++) {
            String path = "village/plains/town_centers/plains_meeting_point_" + variant;
            Optional<HouseTemplate> found = load(world, Identifier.of("minecraft", path));
            if (found.isPresent() && found.get().bellOffset() != null) return found;
        }
        return Optional.empty();
    }

    public static Optional<HouseTemplate> load(ServerWorld world, Identifier id) {
        return load(world, id, null);
    }

    /** Loads a template and converts its vanilla wooden blocks to the local biome's tree family. */
    public static Optional<HouseTemplate> load(ServerWorld world, Identifier id, BlockPos biomePosition) {
        String localWoodFamily = biomePosition == null ? null
                : BiomeWoodProfile.familyForBiome(world.getBiome(biomePosition).getKey()
                        .map(key -> key.getValue().getPath()).orElse("forest"));
        return world.getServer().getStructureTemplateManager().getTemplate(id).map(template -> {
            List<StructureTemplate.StructureBlockInfo> infos = new ArrayList<>();
            StructureTemplateAccessor accessor = (StructureTemplateAccessor) template;
            List<StructureTemplate.PalettedBlockInfoList> palettes = accessor.villageExpansion$getBlockInfoLists();
            // Palettes are alternatives for a template, not additive layers.
            // Loading all of them duplicates blocks at the same coordinates and
            // causes the final house to depend on placement order.
            if (!palettes.isEmpty()) infos.addAll(palettes.get(0).getAll());

            List<TemplateBlock> blocks = new ArrayList<>();
            BlockPos bed = null;
            BlockPos door = null;
            Direction doorFacing = Direction.SOUTH;
            for (StructureTemplate.StructureBlockInfo info : infos) {
                BlockState state = info.state();
                if (localWoodFamily != null) state = adaptWoodState(state, localWoodFamily);
                if (state.isAir() || state.getBlock() == net.minecraft.block.Blocks.STRUCTURE_BLOCK
                        || state.getBlock() == net.minecraft.block.Blocks.JIGSAW) {
                    continue;
                }
                blocks.add(new TemplateBlock(info.pos().toImmutable(), state));
                if (state.isIn(net.minecraft.registry.tag.BlockTags.BEDS)
                        && state.contains(net.minecraft.block.BedBlock.PART)
                        && state.get(net.minecraft.block.BedBlock.PART) == BedPart.HEAD) {
                    bed = info.pos().toImmutable();
                }
                if (state.getBlock() instanceof DoorBlock && state.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    door = info.pos().toImmutable();
                    doorFacing = state.get(DoorBlock.FACING);
                }
            }
            blocks.sort(Comparator.comparingInt((TemplateBlock b) -> b.offset().getY())
                    .thenComparingInt(b -> b.offset().getZ())
                    .thenComparingInt(b -> b.offset().getX()));
            BlockPos workstation = null;
            BlockPos bell = null;
            Identifier professionId = null;
            for (TemplateBlock block : blocks) {
                if (isWorkstation(block.state())) {
                    Identifier candidateProfession = professionForWorkstation(block.state());
                    boolean configuredExternalStation = ProfessionCompatibilityRegistry
                            .professionForWorkstation(block.state().getBlock()).isPresent();
                    if (workstation == null || configuredExternalStation) {
                        workstation = block.offset();
                        professionId = candidateProfession;
                    }
                }
                if (block.state().isOf(Blocks.BELL) && bell == null) bell = block.offset();
            }
            return new HouseTemplate(id, template.getSize(), blocks, bed, door, doorFacing,
                    workstation, bell, professionId);
        });
    }


    public static BlockState adaptWoodState(BlockState original, String targetFamily) {
        if (original == null || original.isAir()) return original;
        Identifier originalId = Registries.BLOCK.getId(original.getBlock());
        if (!"minecraft".equals(originalId.getNamespace())) return original;
        String remappedPath = BiomeWoodProfile.remapVanillaBlockPath(originalId.getPath(), targetFamily);
        if (remappedPath.equals(originalId.getPath())) return original;
        Identifier remappedId = Identifier.of("minecraft", remappedPath);
        net.minecraft.block.Block replacement = Registries.BLOCK.getOrEmpty(remappedId).orElse(null);
        if (replacement == null || replacement == Blocks.AIR) return original;

        BlockState adapted = replacement.getDefaultState();
        for (var entry : original.getEntries().entrySet()) {
            if (adapted.contains(entry.getKey())) {
                adapted = copyStateProperty(adapted, entry.getKey(), entry.getValue());
            }
        }
        return adapted;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState copyStateProperty(BlockState target, Property property, Comparable value) {
        try {
            return target.with(property, value);
        } catch (IllegalArgumentException ignored) {
            return target;
        }
    }

    public static String villageStyle(ServerWorld world, BlockPos pos) {
        String biome = world.getBiome(pos).getKey().map(key -> key.getValue().getPath()).orElse("plains");
        if (biome.contains("desert")) return "desert";
        if (biome.contains("savanna")) return "savanna";
        // Cherry Grove uses the plains layout as a base, then its wooden palette
        // is converted to cherry wood. Do not accidentally pick a taiga layout
        // simply because its biome id contains the word "grove".
        if (biome.contains("cherry")) return "plains";
        if (biome.contains("taiga") || biome.equals("grove") || biome.contains("old_growth_pine")
                || biome.contains("old_growth_spruce")) return "taiga";
        if (biome.contains("snow") || biome.contains("ice_spikes") || biome.contains("frozen")) return "snowy";
        return "plains";
    }

    private static boolean isWorkstation(BlockState state) {
        return state.isOf(Blocks.COMPOSTER) || state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.SMITHING_TABLE) || state.isOf(Blocks.GRINDSTONE)
                || state.isOf(Blocks.SMOKER) || state.isOf(Blocks.BARREL)
                || state.isOf(Blocks.CARTOGRAPHY_TABLE) || state.isOf(Blocks.FLETCHING_TABLE)
                || state.isOf(Blocks.LECTERN) || state.isOf(Blocks.STONECUTTER)
                || state.isOf(Blocks.CAULDRON) || state.isOf(Blocks.BREWING_STAND)
                || state.isOf(Blocks.LOOM) || ProfessionCompatibilityRegistry.isRegisteredWorkstation(state.getBlock());
    }

    public record TemplateBlock(BlockPos offset, BlockState state) {}

    private static Identifier professionForWorkstation(BlockState state) {
        Optional<Identifier> configured = ProfessionCompatibilityRegistry.professionForWorkstation(state.getBlock());
        if (configured.isPresent()) return configured.get();
        if (state.isOf(Blocks.COMPOSTER)) return Identifier.of("minecraft", "farmer");
        if (state.isOf(Blocks.BLAST_FURNACE)) return Identifier.of("minecraft", "armorer");
        if (state.isOf(Blocks.SMITHING_TABLE)) return Identifier.of("minecraft", "toolsmith");
        if (state.isOf(Blocks.GRINDSTONE)) return Identifier.of("minecraft", "weaponsmith");
        if (state.isOf(Blocks.SMOKER)) return Identifier.of("minecraft", "butcher");
        if (state.isOf(Blocks.BARREL)) return Identifier.of("minecraft", "fisherman");
        if (state.isOf(Blocks.CARTOGRAPHY_TABLE)) return Identifier.of("minecraft", "cartographer");
        if (state.isOf(Blocks.FLETCHING_TABLE)) return Identifier.of("minecraft", "fletcher");
        if (state.isOf(Blocks.LECTERN)) return Identifier.of("minecraft", "librarian");
        if (state.isOf(Blocks.STONECUTTER)) return Identifier.of("minecraft", "mason");
        if (state.isOf(Blocks.CAULDRON)) return Identifier.of("minecraft", "leatherworker");
        if (state.isOf(Blocks.BREWING_STAND)) return Identifier.of("minecraft", "cleric");
        if (state.isOf(Blocks.LOOM)) return Identifier.of("minecraft", "shepherd");
        return ProfessionCompatibilityRegistry.professionForWorkstation(state.getBlock()).orElse(null);
    }

    public record HouseTemplate(Identifier id, Vec3i size, List<TemplateBlock> blocks,
                                BlockPos bedOffset, BlockPos doorOffset, Direction doorFacing,
                                BlockPos workstationOffset, BlockPos bellOffset, Identifier professionId) {
        public boolean hasUsefulHomeFeatures() {
            return bedOffset != null && !blocks.isEmpty();
        }

        public BlockPos worldBed(BlockPos origin) {
            return bedOffset == null ? null : origin.add(bedOffset);
        }

        public BlockPos pathGoal(BlockPos origin) {
            if (doorOffset == null) {
                return origin.add(size.getX() / 2, 0, size.getZ() + 1);
            }
            return origin.add(doorOffset).offset(doorFacing);
        }
    }
}
