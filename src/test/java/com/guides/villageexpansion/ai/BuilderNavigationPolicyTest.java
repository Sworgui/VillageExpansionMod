package com.guides.villageexpansion.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderNavigationPolicyTest {
    @Test
    void navigationArbitrationDoesNotRunEveryEntityTick() {
        long nextCheckAt = BuilderNavigationPolicy.nextNavigationCheckAt(100L);

        assertEquals(120L, nextCheckAt);
        assertFalse(BuilderNavigationPolicy.isNavigationCheckDue(100L, nextCheckAt));
        assertFalse(BuilderNavigationPolicy.isNavigationCheckDue(119L, nextCheckAt));
        assertTrue(BuilderNavigationPolicy.isNavigationCheckDue(120L, nextCheckAt));
    }

    @Test
    void timedNavigationReassertionWaitsForTheConfiguredInterval() {
        assertFalse(BuilderNavigationPolicy.shouldReassertNavigation(139L, 100L, 40L));
        assertTrue(BuilderNavigationPolicy.shouldReassertNavigation(140L, 100L, 40L));
        assertFalse(BuilderNavigationPolicy.shouldReassertNavigation(100L, 100L, 0L));
        assertTrue(BuilderNavigationPolicy.shouldReassertNavigation(101L, 100L, 0L));
    }

    @Test
    void navigationCheckIntervalIsLongEnoughForThePathToAdvance() {
        assertEquals(20L, BuilderNavigationPolicy.NAVIGATION_CHECK_INTERVAL_TICKS);
    }
}
