package com.guides.villageexpansion.structures;

import java.util.List;

/** Pure biome-to-wood selection and vanilla wood block-id adaptation. */
public final class BiomeWoodProfile {
    private static final List<String> VANILLA_WOOD_FAMILIES = List.of(
            "dark_oak", "pale_oak", "mangrove", "cherry", "spruce", "birch",
            "jungle", "acacia", "crimson", "warped", "bamboo", "oak");

    private BiomeWoodProfile() { }

    /**
     * Picks the local tree species from a biome registry path. Ordering matters:
     * dark_forest must be checked before forest, and mangrove before swamp.
     */
    public static String familyForBiome(String biomePath) {
        String path = biomePath == null ? "" : biomePath.toLowerCase(java.util.Locale.ROOT);
        if (path.contains("pale_garden")) return "pale_oak";
        if (path.contains("dark_forest")) return "dark_oak";
        if (path.contains("birch")) return "birch";
        if (path.contains("cherry")) return "cherry";
        if (path.contains("mangrove")) return "mangrove";
        if (path.contains("jungle")) return "jungle";
        if (path.contains("savanna")) return "acacia";
        if (path.contains("taiga") || path.equals("grove")
                || path.contains("old_growth_pine") || path.contains("old_growth_spruce")
                || path.contains("snowy") || path.contains("snow")
                || path.contains("frozen")) return "spruce";
        // Regular forest / oak_forest / plains and unknown overworld biomes use oak.
        return "oak";
    }

    /**
     * Rewrites a vanilla wooden block path to the requested local family.
     * Returns the original path when it is not a recognized wooden block.
     */
    public static String remapVanillaBlockPath(String blockPath, String targetFamily) {
        if (blockPath == null || targetFamily == null || targetFamily.isBlank()) return blockPath;
        for (String source : VANILLA_WOOD_FAMILIES) {
            if (source.equals(targetFamily)) return blockPath;
            String prefix = source + "_";
            if (blockPath.startsWith("stripped_" + prefix)) {
                return "stripped_" + targetFamily + blockPath.substring(("stripped_" + source).length());
            }
            if (blockPath.startsWith(prefix)) {
                return targetFamily + blockPath.substring(source.length());
            }
        }
        return blockPath;
    }

    /**
     * Returns a bounded, deterministic slice of a center-out square spiral.
     * Consecutive calls with increasing cursors cover every block column exactly
     * once before wrapping, without allocating a radius-sized search grid.
     */
    public static int[][] searchSpiralOffsets(int radius, int cursor, int maxPoints) {
        int safeRadius = Math.max(1, radius);
        int diameter = safeRadius * 2 + 1;
        int total = diameter * diameter;
        int count = Math.max(0, Math.min(maxPoints, total));
        if (count == 0) return new int[0][2];

        int start = Math.max(0, cursor);
        if (start >= total) return new int[0][2];
        count = Math.min(count, total - start);
        int[][] offsets = new int[count][2];
        for (int i = 0; i < count; i++) {
            int index = start + i;
            int[] point = squareSpiralPoint(index);
            // The first (2r+1)^2 spiral points exactly fill this square.
            offsets[i][0] = Math.max(-safeRadius, Math.min(safeRadius, point[0]));
            offsets[i][1] = Math.max(-safeRadius, Math.min(safeRadius, point[1]));
        }
        return offsets;
    }

    /**
     * Returns {radius, cursor} for the next retry. A larger radius is only
     * selected after the previous square has actually been covered by slices.
     * This prevents the expansion schedule from skipping unvisited forest columns.
     */
    public static int[] searchProgressForRetry(int configuredRadius, int failedSearches,
                                               int pointsPerSlice, int maxRadius) {
        int max = Math.max(1, maxRadius);
        int base = Math.max(1, Math.min(max, configuredRadius));
        int sliceSize = Math.max(1, pointsPerSlice);
        long remainingFailures = Math.max(0, failedSearches);
        int radius = base;
        long cursor = 0L;

        while (radius < max) {
            long total = (long) (radius * 2 + 1) * (radius * 2 + 1);
            long remainingColumns = total - cursor;
            long attemptsNeeded = (remainingColumns + sliceSize - 1L) / sliceSize;
            if (remainingFailures < attemptsNeeded) {
                cursor += remainingFailures * sliceSize;
                return new int[]{radius, (int) Math.min(Integer.MAX_VALUE, cursor)};
            }

            remainingFailures -= attemptsNeeded;
            cursor = total; // The current square is fully searched.
            radius = Math.min(max, radius * 2);
        }

        long total = (long) (radius * 2 + 1) * (radius * 2 + 1);
        long remainingColumns = total - cursor;
        long attemptsNeeded = (remainingColumns + sliceSize - 1L) / sliceSize;
        if (attemptsNeeded <= 0L || remainingFailures >= attemptsNeeded) {
            // The capped radius has also been exhausted. Start another pass so
            // newly loaded chunks or newly grown trees can be discovered later.
            remainingFailures = attemptsNeeded <= 0L ? 0L : remainingFailures % attemptsNeeded;
            cursor = 0L;
        }
        cursor += remainingFailures * sliceSize;
        if (cursor >= total) cursor = 0L;
        return new int[]{radius, (int) Math.min(Integer.MAX_VALUE, cursor)};
    }

    private static int[] squareSpiralPoint(int index) {
        if (index <= 0) return new int[]{0, 0};
        int ring = (int) Math.ceil((Math.sqrt(index + 1.0D) - 1.0D) / 2.0D);
        int side = ring * 2;
        long maximum = (long) (side + 1) * (side + 1) - 1L;
        long distance = maximum - index;
        if (distance < side) {
            return new int[]{ring - (int) distance, -ring};
        }
        if (distance < side * 2L) {
            return new int[]{-ring, -ring + (int) (distance - side)};
        }
        if (distance < side * 3L) {
            return new int[]{-ring + (int) (distance - side * 2L), ring};
        }
        return new int[]{ring, ring - (int) (distance - side * 3L)};
    }

}
