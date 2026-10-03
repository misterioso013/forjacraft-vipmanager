package com.forjacraft.vipmanager.storage;

import com.forjacraft.vipmanager.service.TierState;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence boundary for VIP balances. All methods are expected to be called
 * off the main thread (JDBC/SQLite I/O) - callers are responsible for hopping
 * back to the main thread for any Bukkit-visible side effect afterwards.
 */
public interface VipStorage {

    void init();

    void close();

    /** All tier balances for a player, keyed by tier id. Missing tiers are simply absent, not zeroed rows. */
    Map<String, TierState> loadBalances(UUID uuid);

    /** Upserts a single tier's balance row for a player. */
    void saveBalance(UUID uuid, String tier, TierState state);

    /** Deletes a single tier's balance row entirely (used by /vipadmin remove ... all and migration cleanup). */
    void deleteBalance(UUID uuid, String tier);

    /**
     * UUIDs of every player who currently has any tier marked active, across
     * the whole datastore (not just online players) - this is what the sweep
     * task iterates.
     */
    Set<UUID> allPlayersWithActiveTier();

    void appendAudit(AuditEvent event);

    /**
     * Epoch millis of the last successful claim of this kit by this player, or empty if never claimed.
     * Wall-clock based - only used by {@link com.forjacraft.vipmanager.kit.FreeKitService} (kits with
     * no tier/active concept). VIP tier kits use {@link #getKitCooldown} instead.
     */
    java.util.Optional<Long> getKitClaimTime(UUID uuid, String kitId);

    /** Records a successful claim (upsert). Same scope note as {@link #getKitClaimTime}. */
    void recordKitClaim(UUID uuid, String kitId, long claimedAtMillis);

    /**
     * A VIP tier kit's cooldown, modeled the same way a tier's own VIP balance is
     * ({@code bankedSeconds} = cooldown seconds remaining, ticking down only while
     * {@code active} - i.e. only while the kit's owning tier is the active one).
     * Empty if the kit was never claimed. See {@link com.forjacraft.vipmanager.kit.KitCooldownSync}.
     */
    java.util.Optional<TierState> getKitCooldown(UUID uuid, String kitId);

    /** Upserts a single kit's cooldown row for a player. */
    void saveKitCooldown(UUID uuid, String kitId, TierState state);
}
