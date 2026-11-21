package com.guides.villageexpansion.ai;

/** Pure recovery policy shared by the controller and regression tests. */
public final class BuilderRecoveryPolicy {
    public enum Stage {
        CLEAR_PATH,
        LEVEL_SITE,
        BUILD_PATH,
        GATHER_WOOD,
        GATHER_DIRT,
        GATHER_STONE,
        GATHER_MATERIAL,
        GATHER_FROM_PROFESSIONAL,
        SMELT_MATERIAL,
        MINE_TUNNEL,
        BUILD
    }

    public enum Action {
        ADVANCE_TO_LEVEL_SITE,
        REJECT_LEVEL_STAND,
        SKIP_PATH_OBSTACLE,
        REJECT_RESOURCE_TARGET,
        FALL_BACK_TO_SELF_ACQUISITION,
        RESUME_AFTER_SMELTING,
        RESET_MINE_AND_RESCAN,
        REJECT_CONSTRUCTION_STAND
    }

    private BuilderRecoveryPolicy() {
    }

    public static Action forNavigationFailure(Stage stage) {
        return switch (stage) {
            case CLEAR_PATH -> Action.ADVANCE_TO_LEVEL_SITE;
            case LEVEL_SITE -> Action.REJECT_LEVEL_STAND;
            case BUILD_PATH -> Action.SKIP_PATH_OBSTACLE;
            case GATHER_WOOD, GATHER_DIRT, GATHER_STONE, GATHER_MATERIAL -> Action.REJECT_RESOURCE_TARGET;
            case GATHER_FROM_PROFESSIONAL -> Action.FALL_BACK_TO_SELF_ACQUISITION;
            case SMELT_MATERIAL -> Action.RESUME_AFTER_SMELTING;
            case MINE_TUNNEL -> Action.RESET_MINE_AND_RESCAN;
            case BUILD -> Action.REJECT_CONSTRUCTION_STAND;
        };
    }
}
