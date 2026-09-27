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
}
