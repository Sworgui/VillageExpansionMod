package com.guides.villageexpansion.structures;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeWoodProfileTest {
    @Test
    void regularOakForestNeverSelectsSpruceMaterials() {
        assertEquals("oak", BiomeWoodProfile.familyForBiome("plains"));
        assertEquals("oak", BiomeWoodProfile.familyForBiome("meadow"));
        assertEquals("oak", BiomeWoodProfile.familyForBiome("forest"));
        assertEquals("oak", BiomeWoodProfile.familyForBiome("oak_forest"));
        assertEquals("oak", BiomeWoodProfile.familyForBiome("flower_forest"));
        assertEquals("oak_log", BiomeWoodProfile.remapVanillaBlockPath("spruce_log", "oak"));
        assertEquals("oak_planks", BiomeWoodProfile.remapVanillaBlockPath("spruce_planks", "oak"));
    }

    @Test
    void specialForestsUseTheirNativeWoodFamily() {
        assertEquals("birch", BiomeWoodProfile.familyForBiome("old_growth_birch_forest"));
        assertEquals("dark_oak", BiomeWoodProfile.familyForBiome("dark_forest"));
        assertEquals("spruce", BiomeWoodProfile.familyForBiome("old_growth_pine_taiga"));
        assertEquals("jungle", BiomeWoodProfile.familyForBiome("jungle"));
        assertEquals("acacia", BiomeWoodProfile.familyForBiome("savanna_plateau"));
        assertEquals("mangrove", BiomeWoodProfile.familyForBiome("mangrove_swamp"));
        assertEquals("cherry", BiomeWoodProfile.familyForBiome("cherry_grove"));
        assertEquals("pale_oak", BiomeWoodProfile.familyForBiome("pale_garden"));
    }

    @Test
    void remappingPreservesStrippedAndDecorativeWoodBlockSuffixes() {
        assertEquals("stripped_oak_log",
                BiomeWoodProfile.remapVanillaBlockPath("stripped_spruce_log", "oak"));
        assertEquals("oak_door", BiomeWoodProfile.remapVanillaBlockPath("birch_door", "oak"));
        assertEquals("oak_stairs", BiomeWoodProfile.remapVanillaBlockPath("dark_oak_stairs", "oak"));
        assertEquals("oak_hanging_sign", BiomeWoodProfile.remapVanillaBlockPath("cherry_hanging_sign", "oak"));
        assertEquals("stone_bricks", BiomeWoodProfile.remapVanillaBlockPath("stone_bricks", "oak"));
    }

    @Test
    void incrementalTreeSearchSpiralCoversEveryColumnWithoutDuplicates() {
        int radius = 4;
        int total = (radius * 2 + 1) * (radius * 2 + 1);
        Set<String> points = new HashSet<>();
        for (int cursor = 0; cursor < total; cursor += 7) {
            int[][] slice = BiomeWoodProfile.searchSpiralOffsets(radius, cursor, 7);
            for (int[] point : slice) {
                assertTrue(Math.abs(point[0]) <= radius);
                assertTrue(Math.abs(point[1]) <= radius);
                points.add(point[0] + "," + point[1]);
            }
        }
        assertEquals(total, points.size());
        assertTrue(points.contains("0,0"));
        assertTrue(points.contains("4,4"));
        assertTrue(points.contains("-4,-4"));
    }

    @Test
    void resumedTreeSearchSliceStartsAtTheNextSpiralPosition() {
        int[][] full = BiomeWoodProfile.searchSpiralOffsets(3, 0, 49);
        int[][] first = BiomeWoodProfile.searchSpiralOffsets(3, 0, 8);
        int[][] next = BiomeWoodProfile.searchSpiralOffsets(3, 8, 8);
        assertEquals(0, first[0][0]);
        assertEquals(0, first[0][1]);
        assertEquals(1, first[1][0]);
        assertEquals(0, first[1][1]);
        assertEquals(full[8][0], next[0][0]);
        assertEquals(full[8][1], next[0][1]);
        assertFalse(Arrays.deepEquals(first, next));
    }
    @Test
    void retryProgressSupportsSmallAndMaximumConfiguredRadii() {
        int totalAtMaximumRadius = (96 * 2 + 1) * (96 * 2 + 1);
        for (int configuredRadius : new int[]{8, 96}) {
            Set<String> visited = new HashSet<>();
            for (int failures = 0; failures < 100; failures++) {
                int[] progress = BiomeWoodProfile.searchProgressForRetry(
                        configuredRadius, failures, 1024, 96);
                int[][] slice = BiomeWoodProfile.searchSpiralOffsets(
                        progress[0], progress[1], 1024);
                for (int[] point : slice) {
                    assertTrue(visited.add(point[0] + "," + point[1]),
                            "duplicate column for configured radius " + configuredRadius);
                }
                if (visited.size() == totalAtMaximumRadius) break;
            }
            assertEquals(totalAtMaximumRadius, visited.size());
        }
    }

    @Test
    void retrySlicesExhaustEachRadiusBeforeExpandingAndCoverTheCappedArea() {
        Set<String> visited = new HashSet<>();
        int previousRadius = 32;
        boolean reached64 = false;
        boolean reached96 = false;
        for (int failures = 0; failures < 100; failures++) {
            int[] progress = BiomeWoodProfile.searchProgressForRetry(32, failures, 1024, 96);
            int radius = progress[0];
            int cursor = progress[1];
            if (radius == 64) reached64 = true;
            if (radius == 96) reached96 = true;
            assertTrue(radius >= previousRadius);
            previousRadius = radius;
            int[][] slice = BiomeWoodProfile.searchSpiralOffsets(radius, cursor, 1024);
            for (int[] point : slice) {
                String key = point[0] + "," + point[1];
                // The first complete pass through radius 96 must not revisit any
                // column from the earlier radius slices.
                assertTrue(visited.add(key), "duplicate search column " + key + " at retry " + failures);
            }
            if (visited.size() == (96 * 2 + 1) * (96 * 2 + 1)) break;
        }
        assertTrue(reached64);
        assertTrue(reached96);
        assertEquals((96 * 2 + 1) * (96 * 2 + 1), visited.size());
    }


}
