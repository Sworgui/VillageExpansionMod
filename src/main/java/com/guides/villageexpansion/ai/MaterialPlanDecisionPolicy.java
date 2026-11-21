package com.guides.villageexpansion.ai;

/** Pure decision rules shared by the runtime planner and its unit tests. */
final class MaterialPlanDecisionPolicy {
    private MaterialPlanDecisionPolicy() { }

    /** Prefer a source type that the collection executor knows how to handle over recipe alternatives. */
    static boolean shouldPreferDirectSource(boolean hasDirectSource, double directCost, double impossibleThreshold) {
        return hasDirectSource && Double.isFinite(directCost) && directCost < impossibleThreshold;
    }

    /** Prefer vanilla recipes on equal-cost alternatives while still allowing modded recipes. */
    static double recipeOriginPenalty(String namespace) {
        return "minecraft".equals(namespace) ? 0.0D : 24.0D;
    }

    /** A viable vanilla recipe for an output wins over modded alternatives. */
    static boolean preferRecipeCandidate(boolean candidateVanilla, double candidateScore,
                                          boolean currentVanilla, double currentScore) {
        if (candidateVanilla != currentVanilla) return candidateVanilla;
        return candidateScore < currentScore;
    }

    /**
     * A capped recursive estimate must not turn an unverified recipe into a viable route.
     * Only a known direct source can receive a finite fallback estimate.
     */
    static double budgetFallbackCost(boolean hasDirectSource, int missingCount,
                                     double directUnitCost, double impossibleCost) {
        if (missingCount <= 0) return 0.0D;
        if (!hasDirectSource || !Double.isFinite(directUnitCost) || directUnitCost < 0.0D) {
            return impossibleCost;
        }
        return directUnitCost * missingCount;
    }
}
