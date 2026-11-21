package com.guides.villageexpansion.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaterialGoalStackPolicyTest {
    @Test
    void resumesImmediateParentBeforeOuterGoal() {
        List<MaterialGoalStackPolicy.Goal<String>> stack = new ArrayList<>();
        MaterialGoalStackPolicy.deferParentFirst(stack, "wooden_shovel", 1);
        MaterialGoalStackPolicy.deferParentFirst(stack, "oak_planks", 2);

        assertEquals("oak_planks", MaterialGoalStackPolicy.takeNext(stack).item());
        assertEquals("wooden_shovel", MaterialGoalStackPolicy.takeNext(stack).item());
        assertNull(MaterialGoalStackPolicy.takeNext(stack));
    }

    @Test
    void supportsDeeperDependencyChainsInLifoOrder() {
        List<MaterialGoalStackPolicy.Goal<String>> stack = new ArrayList<>();
        MaterialGoalStackPolicy.deferParentFirst(stack, "wooden_shovel", 1);
        MaterialGoalStackPolicy.deferParentFirst(stack, "oak_planks", 2);
        MaterialGoalStackPolicy.deferParentFirst(stack, "oak_log", 1);

        assertEquals(List.of("oak_log", "oak_planks", "wooden_shovel"),
                stack.stream().map(MaterialGoalStackPolicy.Goal::item).toList());
    }

    @Test
    void dependencyOrderIsIndependentOfWoodSpecies() {
        for (String plank : List.of("oak_planks", "spruce_planks", "birch_planks", "cherry_planks")) {
            List<MaterialGoalStackPolicy.Goal<String>> stack = new ArrayList<>();
            MaterialGoalStackPolicy.deferParentFirst(stack, "wooden_shovel", 1);
            MaterialGoalStackPolicy.deferParentFirst(stack, plank, 2);
            assertEquals(plank, MaterialGoalStackPolicy.takeNext(stack).item());
            assertEquals("wooden_shovel", MaterialGoalStackPolicy.takeNext(stack).item());
        }
    }

    @Test
    void duplicateDeferredGoalUpdatesCountWithoutChangingDependencyOrder() {
        List<MaterialGoalStackPolicy.Goal<String>> stack = new ArrayList<>();
        MaterialGoalStackPolicy.deferParentFirst(stack, "wooden_shovel", 1);
        MaterialGoalStackPolicy.deferParentFirst(stack, "oak_planks", 2);
        MaterialGoalStackPolicy.deferParentFirst(stack, "wooden_shovel", 3);

        assertEquals(2, stack.size());
        assertEquals("oak_planks", stack.get(0).item());
        assertEquals("wooden_shovel", stack.get(1).item());
        assertEquals(3, stack.get(1).count());
    }

    @Test
    void migratesLegacyRootFirstQueueToDependencyFirstStack() {
        List<MaterialGoalStackPolicy.Goal<String>> stack = new ArrayList<>(List.of(
                new MaterialGoalStackPolicy.Goal<>("wooden_shovel", 1),
                new MaterialGoalStackPolicy.Goal<>("oak_planks", 2)));

        MaterialGoalStackPolicy.migrateLegacyFifoOrder(stack);

        assertEquals("oak_planks", stack.get(0).item());
        assertEquals("wooden_shovel", stack.get(1).item());
    }
}
