package com.guides.villageexpansion.ai;

import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialToolCompatibilityTest {
    @Test
    void stoneShovelSatisfiesWoodenShovelGoal() {
        assertTrue(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.STONE_SHOVEL, Items.WOODEN_SHOVEL));
        assertTrue(MaterialAcquisitionPlanner.toolTier(Items.STONE_SHOVEL, Items.WOODEN_SHOVEL)
                > MaterialAcquisitionPlanner.toolTier(Items.WOODEN_SHOVEL, Items.WOODEN_SHOVEL));
    }

    @Test
    void ironAndDiamondShovelsSatisfyLowerTierShovelGoals() {
        assertTrue(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.IRON_SHOVEL, Items.STONE_SHOVEL));
        assertTrue(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.DIAMOND_SHOVEL, Items.WOODEN_SHOVEL));
    }

    @Test
    void stoneShovelIsBetterThanWoodButStonePickaxeCannotMineDiamondOre() {
        assertTrue(MaterialAcquisitionPlanner.toolTier(Items.STONE_SHOVEL, Items.WOODEN_SHOVEL)
                > MaterialAcquisitionPlanner.toolTier(Items.WOODEN_SHOVEL, Items.WOODEN_SHOVEL));
        assertTrue(MaterialAcquisitionPlanner.toolTier(Items.STONE_PICKAXE, Items.IRON_PICKAXE)
                < MaterialAcquisitionPlanner.toolTier(Items.IRON_PICKAXE, Items.IRON_PICKAXE));
        assertTrue(MaterialAcquisitionPlanner.toolTier(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE)
                < MaterialAcquisitionPlanner.toolTier(Items.DIAMOND_PICKAXE, Items.DIAMOND_PICKAXE));
        assertTrue(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.IRON_PICKAXE, Items.STONE_PICKAXE));
        assertFalse(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.STONE_PICKAXE, Items.IRON_PICKAXE));
        assertFalse(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE));
    }


    @Test
    void goldenToolsRemainWoodenHarvestTierDespiteTheirMiningSpeed() {
        assertTrue(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.GOLDEN_SHOVEL, Items.WOODEN_SHOVEL));
        assertFalse(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.GOLDEN_SHOVEL, Items.STONE_SHOVEL));
        assertTrue(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.GOLDEN_AXE, Items.WOODEN_AXE));
        assertFalse(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.GOLDEN_AXE, Items.STONE_AXE));
        assertFalse(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.GOLDEN_PICKAXE, Items.IRON_PICKAXE));
    }

    @Test
    void toolsFromDifferentFamiliesAreNeverInterchangeable() {
        assertFalse(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.STONE_SHOVEL, Items.WOODEN_AXE));
        assertFalse(MaterialAcquisitionPlanner.isCompatibleProviderItem(Items.IRON_PICKAXE, Items.WOODEN_SHOVEL));
    }

    @Test
    void ironPickaxeIsRequiredForDiamondOreAndDiamondPickaxeForObsidian() {
        assertEquals(Items.IRON_PICKAXE, MaterialAcquisitionPlanner.requiredToolFor(Items.DIAMOND));
        assertEquals(Items.DIAMOND_PICKAXE, MaterialAcquisitionPlanner.requiredToolFor(Items.OBSIDIAN));
        assertEquals(MaterialAcquisitionPlanner.SourceKind.MINE,
                MaterialAcquisitionPlanner.sourceFor(Items.OBSIDIAN));
    }
}
