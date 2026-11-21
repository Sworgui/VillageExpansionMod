package com.guides.villageexpansion.ai;

/**
 * Pure timing policy for arbitration between a builder route and the vanilla
 * Villager Brain. Navigation paths need several ticks to make progress; issuing
 * the same route every entity tick can repeatedly rebuild the path and prevent
 * movement.
 */
public final class BuilderNavigationPolicy {
    public static final long NAVIGATION_CHECK_INTERVAL_TICKS = 20L;

    private BuilderNavigationPolicy() {
    }

    public static boolean isNavigationCheckDue(long now, long nextCheckAt) {
        return now >= nextCheckAt;
    }

    public static long nextNavigationCheckAt(long now) {
        return now + NAVIGATION_CHECK_INTERVAL_TICKS;
    }

    public static boolean shouldReassertNavigation(long now, long lastOverrideAt, long reassertInterval) {
        return now - lastOverrideAt >= Math.max(1L, reassertInterval);
    }
}
