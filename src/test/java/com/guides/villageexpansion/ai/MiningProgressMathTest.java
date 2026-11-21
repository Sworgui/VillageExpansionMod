package com.guides.villageexpansion.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningProgressMathTest {
    @Test
    void suitableWoodenAxeUsesVanillaSuitableToolRate() {
        // Vanilla wooden axe multiplier on a log is 2; hardness 2 => 30 ticks.
        assertEquals(30, MiningProgressMath.ticksToBreak(2.0F, true, 2.0F));
    }

    @Test
    void noToolUsesVanillaUnsuitableToolRate() {
        // Empty hand multiplier 1; hardness 2 => 200 ticks.
        assertEquals(200, MiningProgressMath.ticksToBreak(2.0F, false, 1.0F));
    }

    @Test
    void fasterToolReducesBreakDurationProportionally() {
        assertEquals(10, MiningProgressMath.ticksToBreak(2.0F, true, 6.0F));
        assertEquals(5, MiningProgressMath.ticksToBreak(2.0F, true, 12.0F));
    }

    @Test
    void actualToolSpeedIsUsedRatherThanAConfiguredFixedDuration() {
        // Same block, same suitability, different tool multipliers: duration scales inversely.
        assertEquals(30, MiningProgressMath.ticksToBreak(2.0F, true, 2.0F));
        assertEquals(15, MiningProgressMath.ticksToBreak(2.0F, true, 4.0F));
        assertEquals(10, MiningProgressMath.ticksToBreak(2.0F, true, 6.0F));
    }

    @Test
    void unsuitableToolUsesTheSlowerVanillaHarvestDivisor() {
        int withSuitableTool = MiningProgressMath.ticksToBreak(1.5F, true, 1.0F);
        int withoutSuitableTool = MiningProgressMath.ticksToBreak(1.5F, false, 1.0F);
        assertEquals(45, withSuitableTool);
        assertEquals(150, withoutSuitableTool);
    }

    @Test
    void zeroHardnessBlockBreaksInOneTick() {
        assertEquals(1, MiningProgressMath.ticksToBreak(0.0F, true, 1.0F));
    }

    @Test
    void unbreakableBlockIsReportedInsteadOfStallingForever() {
        assertEquals(-1, MiningProgressMath.ticksToBreak(-1.0F, true, 8.0F));
    }

    @Test
    void invalidOrTinySpeedIsClamped() {
        assertEquals(600, MiningProgressMath.ticksToBreak(2.0F, true, 0.0F));
    }
}
