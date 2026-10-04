package com.shiroha.mmdskin.compat.vr.mirror;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrMirrorCompileBudgetTest {
    @Test void bothEyesShareOneBudgetUntilTheNextTick() {
        var budget = new VrMirrorCompileBudget();
        assertTrue(budget.tryAcquire(7, 0));
        assertTrue(budget.tryAcquire(7, 10));
        assertTrue(budget.tryAcquire(7, 20));
        assertTrue(budget.tryAcquire(7, 30));
        assertFalse(budget.tryAcquire(7, 40));
        assertFalse(budget.tryAcquire(7, 50));
        assertTrue(budget.tryAcquire(8, 60));
    }

    @Test void slowSubmissionStopsFurtherWorkEvenWithCountRemaining() {
        var budget = new VrMirrorCompileBudget();
        assertTrue(budget.tryAcquire(7, 100));
        assertFalse(budget.tryAcquire(7, 2_000_100));
        assertFalse(budget.tryAcquire(7, 4_000_100));
        assertTrue(budget.tryAcquire(8, 4_000_200));
    }
}
