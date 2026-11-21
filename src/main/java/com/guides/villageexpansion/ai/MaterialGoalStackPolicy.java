package com.guides.villageexpansion.ai;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Orders suspended material goals so the nearest unresolved dependency resumes first.
 * A parent goal is pushed to the front; nested dependencies therefore behave as a
 * stack (LIFO), not a FIFO queue.
 */
final class MaterialGoalStackPolicy {
    private MaterialGoalStackPolicy() { }

    record Goal<T>(T item, int count) {
        Goal {
            count = Math.max(1, count);
        }
    }

    static <T> void deferParentFirst(List<Goal<T>> goals, T item, int count) {
        if (goals == null || item == null) return;
        int safeCount = Math.max(1, count);
        for (int i = 0; i < goals.size(); i++) {
            Goal<T> existing = goals.get(i);
            if (Objects.equals(existing.item(), item)) {
                if (safeCount > existing.count()) {
                    goals.set(i, new Goal<>(item, safeCount));
                }
                return;
            }
        }
        goals.add(0, new Goal<>(item, safeCount));
    }

    static <T> Goal<T> takeNext(List<Goal<T>> goals) {
        return goals == null || goals.isEmpty() ? null : goals.remove(0);
    }

    /** Old saves were written in root-first FIFO order. Reverse them once on load. */
    static <T> void migrateLegacyFifoOrder(List<Goal<T>> goals) {
        if (goals != null) Collections.reverse(goals);
    }
}
