package com.guides.villageexpansion.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the planner's vanilla tool rules without loading Minecraft registries.
 * This keeps the tests compatible with the remapped Fabric Loom JUnit runtime.
 */
class MaterialToolCompatibilityTest {
    @Test
    void stoneShovelSatisfiesWoodenShovelGoal() {
        assertTrue(compatible("stone_shovel", "wooden_shovel"));
        assertTrue(tier("stone_shovel", "wooden_shovel")
                > tier("wooden_shovel", "wooden_shovel"));
    }

    @Test
    void ironAndDiamondShovelsSatisfyLowerTierShovelGoals() {
        assertTrue(compatible("iron_shovel", "stone_shovel"));
        assertTrue(compatible("diamond_shovel", "wooden_shovel"));
    }

    @Test
    void stoneShovelIsBetterThanWoodButStonePickaxeCannotMineDiamondOre() {
        assertTrue(tier("stone_shovel", "wooden_shovel")
                > tier("wooden_shovel", "wooden_shovel"));
        assertTrue(tier("stone_pickaxe", "iron_pickaxe")
                < tier("iron_pickaxe", "iron_pickaxe"));
        assertTrue(tier("iron_pickaxe", "diamond_pickaxe")
                < tier("diamond_pickaxe", "diamond_pickaxe"));
        assertTrue(compatible("iron_pickaxe", "stone_pickaxe"));
        assertFalse(compatible("stone_pickaxe", "iron_pickaxe"));
        assertFalse(compatible("iron_pickaxe", "diamond_pickaxe"));
    }

    @Test
    void goldenToolsRemainWoodenHarvestTierDespiteTheirMiningSpeed() {
        assertTrue(compatible("golden_shovel", "wooden_shovel"));
        assertFalse(compatible("golden_shovel", "stone_shovel"));
        assertTrue(compatible("golden_axe", "wooden_axe"));
        assertFalse(compatible("golden_axe", "stone_axe"));
        assertFalse(compatible("golden_pickaxe", "iron_pickaxe"));
    }

    @Test
    void toolsFromDifferentFamiliesAreNeverInterchangeable() {
        assertFalse(compatible("stone_shovel", "wooden_axe"));
        assertFalse(compatible("iron_pickaxe", "wooden_shovel"));
    }

    @Test
    void ironPickaxeIsRequiredForDiamondOreAndDiamondPickaxeForObsidian() {
        assertEquals(tier("iron_pickaxe", "iron_pickaxe"),
                MaterialToolRules.requiredPickaxeTierForMaterial("minecraft:diamond_ore"));
        assertEquals(tier("diamond_pickaxe", "diamond_pickaxe"),
                MaterialToolRules.requiredPickaxeTierForMaterial("minecraft:obsidian"));
    }

    private static boolean compatible(String offered, String wanted) {
        return MaterialToolRules.isCompatibleProviderItem(id(offered), id(wanted));
    }

    private static int tier(String candidate, String familyReference) {
        return MaterialToolRules.toolTier(id(candidate), id(familyReference));
    }

    private static String id(String path) {
        return "minecraft:" + path;
    }
}
