package com.guides.villageexpansion.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialPlanDecisionPolicyTest {
    private static final double IMPOSSIBLE = 1.0e12D;

    @Test
    void directSourceIsPreferredWheneverTheExecutorSupportsThatSource() {
        assertTrue(MaterialPlanDecisionPolicy.shouldPreferDirectSource(true, 24.0D, 1.0e11D));
        assertFalse(MaterialPlanDecisionPolicy.shouldPreferDirectSource(false, 24.0D, 1.0e11D));
        assertFalse(MaterialPlanDecisionPolicy.shouldPreferDirectSource(true, IMPOSSIBLE, 1.0e11D));
    }

    @Test
    void exhaustedPlanningBudgetNeverInventsARecipeRoute() {
        assertEquals(IMPOSSIBLE,
                MaterialPlanDecisionPolicy.budgetFallbackCost(false, 2, 24.0D, IMPOSSIBLE));
    }

    @Test
    void directSourceFallbackRemainsFiniteWhenRecursiveBudgetIsExhausted() {
        assertEquals(72.0D,
                MaterialPlanDecisionPolicy.budgetFallbackCost(true, 3, 24.0D, IMPOSSIBLE));
    }

    @Test
    void vanillaRecipesWinEqualCostTiesWithoutDisablingModdedRecipes() {
        assertEquals(0.0D, MaterialPlanDecisionPolicy.recipeOriginPenalty("minecraft"));
        assertTrue(MaterialPlanDecisionPolicy.recipeOriginPenalty("some_mod") > 0.0D);
    }

    @Test
    void viableVanillaRecipeWinsOverCheaperModdedAlternative() {
        assertTrue(MaterialPlanDecisionPolicy.preferRecipeCandidate(true, 180.0D, false, 10.0D));
        assertFalse(MaterialPlanDecisionPolicy.preferRecipeCandidate(false, 10.0D, true, 180.0D));
    }

    @Test
    void scoresChooseBetweenRecipesFromTheSameOrigin() {
        assertTrue(MaterialPlanDecisionPolicy.preferRecipeCandidate(true, 10.0D, true, 11.0D));
        assertFalse(MaterialPlanDecisionPolicy.preferRecipeCandidate(false, 11.0D, false, 10.0D));
    }

    @Test
    void emptyRequirementHasZeroFallbackCost() {
        assertEquals(0.0D,
                MaterialPlanDecisionPolicy.budgetFallbackCost(false, 0, 24.0D, IMPOSSIBLE));
    }
}
