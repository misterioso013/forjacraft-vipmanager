package com.forjacraft.vipmanager.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VipTimeMathTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @Test
    void inactiveTierNeverMovesOnReconcile() {
        TierState inactive = TierState.inactive(1000L);
        VipTimeMath.ReconcileResult result = VipTimeMath.reconcile(inactive, System.currentTimeMillis() + DAY_MS);
        assertEquals(1000L, result.state().bankedSeconds());
        assertFalse(result.state().active());
        assertFalse(result.expired());
    }

    @Test
    void activeTierLosesElapsedTimeOnReconcile() {
        long start = 0L;
        TierState active = TierState.activeSince(3600L, start); // 1 hour banked
        long later = start + 1800L * 1000L; // 30 minutes later

        VipTimeMath.ReconcileResult result = VipTimeMath.reconcile(active, later);

        assertFalse(result.expired());
        assertTrue(result.state().active());
        assertEquals(1800L, result.state().bankedSeconds());
        assertEquals(later, result.state().activeSince());
    }

    @Test
    void activeTierExpiresExactlyAtZero() {
        TierState active = TierState.activeSince(60L, 0L);
        VipTimeMath.ReconcileResult result = VipTimeMath.reconcile(active, 60_000L);

        assertTrue(result.expired());
        assertEquals(0L, result.state().bankedSeconds());
        assertFalse(result.state().active());
    }

    @Test
    void offlineServerDowntimeIsCorrectlyDeducted() {
        // Simulates: tier activated with 5 days banked, server goes down,
        // comes back 3 days later - should have exactly 2 days left, no tick
        // counting involved.
        long activatedAt = 0L;
        TierState active = TierState.activeSince(5 * 24 * 3600L, activatedAt);

        long serverBackOnline = activatedAt + 3 * DAY_MS;
        VipTimeMath.ReconcileResult result = VipTimeMath.reconcile(active, serverBackOnline);

        assertFalse(result.expired());
        assertEquals(2 * 24 * 3600L, result.state().bankedSeconds());
    }

    @Test
    void offlineServerDowntimeLongerThanBalanceExpiresIt() {
        long activatedAt = 0L;
        TierState active = TierState.activeSince(2 * 24 * 3600L, activatedAt); // 2 days banked

        long serverBackOnline = activatedAt + 10 * DAY_MS; // down for 10 days
        VipTimeMath.ReconcileResult result = VipTimeMath.reconcile(active, serverBackOnline);

        assertTrue(result.expired());
        assertEquals(0L, result.state().bankedSeconds());
    }

    @Test
    void deactivateCheckspointsRemainingBalanceAndPauses() {
        TierState active = TierState.activeSince(1000L, 0L);
        VipTimeMath.ReconcileResult result = VipTimeMath.deactivate(active, 400_000L); // 400s elapsed

        assertFalse(result.expired());
        assertFalse(result.state().active());
        assertEquals(600L, result.state().bankedSeconds());
    }

    @Test
    void deactivatingAnAlreadyExpiredTierReportsExpiry() {
        TierState active = TierState.activeSince(100L, 0L);
        VipTimeMath.ReconcileResult result = VipTimeMath.deactivate(active, 200_000L); // 200s elapsed > 100s banked

        assertTrue(result.expired());
        assertEquals(0L, result.state().bankedSeconds());
    }

    @Test
    void activateRequiresPositiveBalance() {
        TierState empty = TierState.inactive(0L);
        assertThrows(IllegalArgumentException.class, () -> VipTimeMath.activate(empty, 0L));
    }

    @Test
    void activateSetsActiveFlagAndAnchor() {
        TierState inactive = TierState.inactive(500L);
        TierState activated = VipTimeMath.activate(inactive, 12345L);

        assertTrue(activated.active());
        assertEquals(500L, activated.bankedSeconds());
        assertEquals(12345L, activated.activeSince());
    }

    @Test
    void addSecondsOnInactiveTierJustAddsToBalance() {
        TierState inactive = TierState.inactive(1000L);
        VipTimeMath.ReconcileResult result = VipTimeMath.addSeconds(inactive, 500L, 99_999L);

        assertFalse(result.state().active());
        assertEquals(1500L, result.state().bankedSeconds());
    }

    @Test
    void addSecondsOnActiveTierReconcilesFirstThenAdds() {
        // 1000s banked, active since t=0. Adding at t=400s (400s elapsed) should
        // land on 600 (remaining) + 500 (added) = 1100, still active, re-anchored.
        TierState active = TierState.activeSince(1000L, 0L);
        VipTimeMath.ReconcileResult result = VipTimeMath.addSeconds(active, 500L, 400_000L);

        assertFalse(result.expired());
        assertTrue(result.state().active());
        assertEquals(1100L, result.state().bankedSeconds());
        assertEquals(400_000L, result.state().activeSince());
    }

    @Test
    void addSecondsRejectsNonPositiveDelta() {
        TierState inactive = TierState.inactive(0L);
        assertThrows(IllegalArgumentException.class, () -> VipTimeMath.addSeconds(inactive, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> VipTimeMath.addSeconds(inactive, -10L, 0L));
    }

    @Test
    void reconcileRejectsClockGoingBackwards() {
        TierState active = TierState.activeSince(100L, 10_000L);
        assertThrows(IllegalArgumentException.class, () -> VipTimeMath.reconcile(active, 5_000L));
    }

    @Test
    void remainingSecondsIsReadOnlyAndDoesNotMutate() {
        TierState active = TierState.activeSince(1000L, 0L);
        long remaining = VipTimeMath.remainingSeconds(active, 400_000L);

        assertEquals(600L, remaining);
        // original state object is a record - immutable by construction, but
        // assert the call didn't somehow return something claiming to be active
        // past its balance:
        assertEquals(1000L, active.bankedSeconds());
    }

    @Test
    void remainingSecondsNeverGoesNegative() {
        TierState active = TierState.activeSince(100L, 0L);
        long remaining = VipTimeMath.remainingSeconds(active, 10_000_000L);
        assertEquals(0L, remaining);
    }
}
