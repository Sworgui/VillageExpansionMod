package com.guides.villageexpansion.ai;

/**
 * Mirrors the vanilla survival block-breaking relationship for a single tool.
 * The caller advances this many one-tick steps while the villager remains at work.
 */
public final class MiningProgressMath {
    private MiningProgressMath() {
    }

    /**
     * @return number of game ticks required, or -1 for an unbreakable block.
     */
    /** Keep work when the target coordinate and underlying block identity are unchanged. */
    public static boolean shouldRetainSession(boolean samePosition, boolean sameBlockType) {
        return samePosition && sameBlockType;
    }

    /** Pause (rather than reset) when knockback moves the worker beyond local reach. */
    public static boolean isWithinHorizontalReach(double horizontalDistanceSquared) {
        return horizontalDistanceSquared <= 16.0D;
    }

    public static int ticksToBreak(float hardness, boolean suitableTool, float miningSpeed) {
        if (hardness < 0.0F) return -1;
        if (hardness == 0.0F) return 1;
        float safeSpeed = Math.max(0.1F, miningSpeed);
        float vanillaDivisor = suitableTool ? 30.0F : 100.0F;
        return Math.max(1, (int) Math.ceil(hardness * vanillaDivisor / safeSpeed));
    }
}
