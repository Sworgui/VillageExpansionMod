package com.guides.villageexpansion.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuilderRecoveryPolicyTest {
    @Test
    void everyNavigationStageHasAnExplicitRecoveryAction() {
        assertEquals(BuilderRecoveryPolicy.Action.ADVANCE_TO_LEVEL_SITE,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.CLEAR_PATH));
        assertEquals(BuilderRecoveryPolicy.Action.REJECT_LEVEL_STAND,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.LEVEL_SITE));
        assertEquals(BuilderRecoveryPolicy.Action.SKIP_PATH_OBSTACLE,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.BUILD_PATH));
        assertEquals(BuilderRecoveryPolicy.Action.REJECT_RESOURCE_TARGET,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.GATHER_WOOD));
        assertEquals(BuilderRecoveryPolicy.Action.REJECT_RESOURCE_TARGET,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.GATHER_DIRT));
        assertEquals(BuilderRecoveryPolicy.Action.REJECT_RESOURCE_TARGET,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.GATHER_STONE));
        assertEquals(BuilderRecoveryPolicy.Action.REJECT_RESOURCE_TARGET,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.GATHER_MATERIAL));
        assertEquals(BuilderRecoveryPolicy.Action.FALL_BACK_TO_SELF_ACQUISITION,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.GATHER_FROM_PROFESSIONAL));
        assertEquals(BuilderRecoveryPolicy.Action.RESUME_AFTER_SMELTING,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.SMELT_MATERIAL));
        assertEquals(BuilderRecoveryPolicy.Action.RESET_MINE_AND_RESCAN,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.MINE_TUNNEL));
        assertEquals(BuilderRecoveryPolicy.Action.REJECT_CONSTRUCTION_STAND,
                BuilderRecoveryPolicy.forNavigationFailure(BuilderRecoveryPolicy.Stage.BUILD));
    }

    @Test
    void policyCoversEveryStageWithoutAnImplicitNullFallback() {
        for (BuilderRecoveryPolicy.Stage stage : BuilderRecoveryPolicy.Stage.values()) {
            assertNotNull(BuilderRecoveryPolicy.forNavigationFailure(stage),
                    "Missing recovery action for " + stage);
        }
    }

    @Test
    void resourceCollectionStagesNeverRetryTheSameUnreachableTargetForever() {
        for (BuilderRecoveryPolicy.Stage stage : new BuilderRecoveryPolicy.Stage[]{
                BuilderRecoveryPolicy.Stage.GATHER_WOOD,
                BuilderRecoveryPolicy.Stage.GATHER_DIRT,
                BuilderRecoveryPolicy.Stage.GATHER_STONE,
                BuilderRecoveryPolicy.Stage.GATHER_MATERIAL
        }) {
            assertEquals(BuilderRecoveryPolicy.Action.REJECT_RESOURCE_TARGET,
                    BuilderRecoveryPolicy.forNavigationFailure(stage));
        }
    }
}
