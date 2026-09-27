package com.forjacraft.vipmanager.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * GriefPrevention has no permission-based claim-block bonus - it's always a
 * console command (see the server repo's docs/vip_grant_runbook.md). This
 * bridge dispatches that command whenever a tier change should adjust a
 * player's bonus claim blocks, so switching/expiring tiers doesn't silently
 * regress a benefit that's already promised to VIPs.
 *
 * <p>Must be called on the main thread - callers coming from async contexts
 * (the sweep task, join-time reconciliation) hop back via the scheduler.</p>
 */
public final class ClaimBlockBridge {

    private final Plugin plugin;

    public ClaimBlockBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Applies {@code delta} claim blocks to {@code playerName} (positive or
     * negative). A delta of 0 is a no-op and dispatches nothing.
     */
    public void adjust(String playerName, long delta) {
        if (delta == 0) {
            return;
        }
        String command = "adjustclaimblocks " + playerName + " " + delta;
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    /** Overload for callers that only have a UUID and need to resolve a name first. */
    public void adjust(UUID uuid, long delta) {
        if (delta == 0) {
            return;
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        if (name == null) {
            plugin.getLogger().warning("Could not resolve a name for " + uuid + " - skipping claim block adjustment of " + delta);
            return;
        }
        adjust(name, delta);
    }
}
