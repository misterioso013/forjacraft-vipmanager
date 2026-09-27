package com.forjacraft.vipmanager.service;

/**
 * Pure, Bukkit-free time-accounting logic. This is the single most bug-prone
 * part of the whole plugin (pause/resume, offline-server elapsed time, expiry
 * edge cases) and the cheapest to unit test in isolation - keep it free of any
 * Bukkit/LuckPerms dependency so tests can feed it fake clocks.
 *
 * <p>Model: a tier's true remaining balance is always
 * {@code bankedSeconds - elapsedSecondsSinceActiveSince} while active, and just
 * {@code bankedSeconds} while inactive (paused, not ticking). Reconciling
 * "checkpoints" that computation back into a fresh {@link TierState} anchored
 * at "now", which is what makes the model correct across arbitrarily long
 * server downtime - nothing here depends on counting ticks.</p>
 */
public final class VipTimeMath {

    private VipTimeMath() {
    }

    /**
     * Result of reconciling a {@link TierState} against a point in time.
     *
     * @param state    the checkpointed state as of {@code nowMillis} - if the
     *                 input was active and not expired, this is still active
     *                 with {@code activeSince == nowMillis}; if it expired,
     *                 this is {@link TierState#inactive} with zero balance.
     * @param expired  true if the tier's balance reached zero or below during
     *                 this reconciliation (i.e. it was active and ran out).
     */
    public record ReconcileResult(TierState state, boolean expired) {
    }

    /**
     * Reconciles a tier's state up to {@code nowMillis}.
     *
     * <p>If the tier is inactive, it is returned unchanged (paused balances
     * never move on their own). If active, elapsed time since
     * {@code activeSince} is subtracted from the banked balance:</p>
     * <ul>
     *   <li>If the result is {@code > 0}, the state is checkpointed: same
     *       balance, {@code activeSince} advanced to {@code nowMillis}.</li>
     *   <li>If the result is {@code <= 0}, the tier has expired: returns an
     *       inactive, zero-balance state and {@code expired = true}.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if an active state's {@code activeSince}
     *         is null, or if {@code nowMillis} is before {@code activeSince}
     *         (clock went backwards - callers must not feed that in).
     */
    public static ReconcileResult reconcile(TierState state, long nowMillis) {
        if (!state.active()) {
            return new ReconcileResult(state, false);
        }
        Long activeSince = state.activeSince();
        if (activeSince == null) {
            throw new IllegalArgumentException("Active TierState must have a non-null activeSince");
        }
        if (nowMillis < activeSince) {
            throw new IllegalArgumentException(
                    "nowMillis (" + nowMillis + ") is before activeSince (" + activeSince + ")");
        }

        long elapsedSeconds = (nowMillis - activeSince) / 1000L;
        long remaining = state.bankedSeconds() - elapsedSeconds;

        if (remaining <= 0) {
            return new ReconcileResult(TierState.inactive(0L), true);
        }
        return new ReconcileResult(TierState.activeSince(remaining, nowMillis), false);
    }

    /**
     * The remaining seconds an active tier has right now, without mutating
     * anything - convenience for display purposes (e.g. {@code /vip} status,
     * placeholders) where a full reconcile-and-persist isn't wanted on every
     * placeholder read.
     */
    public static long remainingSeconds(TierState state, long nowMillis) {
        if (!state.active()) {
            return state.bankedSeconds();
        }
        Long activeSince = state.activeSince();
        if (activeSince == null) {
            return state.bankedSeconds();
        }
        long elapsedSeconds = (nowMillis - activeSince) / 1000L;
        return Math.max(0L, state.bankedSeconds() - elapsedSeconds);
    }

    /**
     * Activates a currently-inactive tier as of {@code nowMillis}. The banked
     * balance is unchanged; only the active flag and anchor timestamp move.
     *
     * @throws IllegalArgumentException if the tier has no balance to activate.
     */
    public static TierState activate(TierState state, long nowMillis) {
        if (state.bankedSeconds() <= 0) {
            throw new IllegalArgumentException("Cannot activate a tier with zero or negative balance");
        }
        return TierState.activeSince(state.bankedSeconds(), nowMillis);
    }

    /**
     * Deactivates (pauses) a tier, first reconciling it up to {@code nowMillis}
     * so the checkpointed balance reflects time elapsed while it was active.
     * If reconciliation reveals it already expired, returns the expired
     * (zero-balance, inactive) result rather than a stale positive balance.
     */
    public static ReconcileResult deactivate(TierState state, long nowMillis) {
        ReconcileResult reconciled = reconcile(state, nowMillis);
        if (reconciled.expired()) {
            return reconciled;
        }
        return new ReconcileResult(TierState.inactive(reconciled.state().bankedSeconds()), false);
    }

    /**
     * Adds {@code deltaSeconds} (must be positive) to a tier's banked balance.
     * If the tier is currently active, it is first reconciled up to
     * {@code nowMillis} so the addition lands on an up-to-date balance rather
     * than silently un-expiring a tier that actually ran out moments ago.
     */
    public static ReconcileResult addSeconds(TierState state, long deltaSeconds, long nowMillis) {
        if (deltaSeconds <= 0) {
            throw new IllegalArgumentException("deltaSeconds must be positive, got " + deltaSeconds);
        }
        ReconcileResult reconciled = reconcile(state, nowMillis);
        TierState base = reconciled.state();
        long newBalance = base.bankedSeconds() + deltaSeconds;
        if (base.active()) {
            return new ReconcileResult(TierState.activeSince(newBalance, nowMillis), false);
        }
        return new ReconcileResult(TierState.inactive(newBalance), false);
    }
}
