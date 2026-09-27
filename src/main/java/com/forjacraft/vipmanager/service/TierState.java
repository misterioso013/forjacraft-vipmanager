package com.forjacraft.vipmanager.service;

/**
 * Immutable snapshot of a single player's balance for a single tier, as last
 * checkpointed. {@code bankedSeconds} is only accurate as of {@code activeSince}
 * when {@code active} is true - use {@link VipTimeMath#reconcile} to compute the
 * true remaining balance at any later instant.
 */
public record TierState(long bankedSeconds, boolean active, Long activeSince) {

    public static TierState inactive(long bankedSeconds) {
        return new TierState(bankedSeconds, false, null);
    }

    public static TierState activeSince(long bankedSeconds, long activeSinceMillis) {
        return new TierState(bankedSeconds, true, activeSinceMillis);
    }

    public static final TierState EMPTY = inactive(0L);
}
