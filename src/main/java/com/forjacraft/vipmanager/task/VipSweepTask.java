package com.forjacraft.vipmanager.task;

import com.forjacraft.vipmanager.service.VipService;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs periodically (async - see registration in VipManagerPlugin) and
 * reconciles every player who currently has any tier marked active, online or
 * not. Never touches Bukkit.getOnlinePlayers() - membership in "needs a sweep"
 * comes entirely from storage, which is what makes offline expiry work.
 */
public final class VipSweepTask implements Runnable {

    private final VipService vipService;
    private final Logger logger;

    public VipSweepTask(VipService vipService, Logger logger) {
        this.vipService = vipService;
        this.logger = logger;
    }

    @Override
    public void run() {
        Set<UUID> toCheck;
        try {
            toCheck = vipService.allPlayersWithActiveTier();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "VIP sweep failed to list active players - skipping this run", e);
            return;
        }

        for (UUID uuid : toCheck) {
            try {
                vipService.reconcilePlayer(uuid);
            } catch (Exception e) {
                logger.log(Level.WARNING, "VIP sweep failed to reconcile " + uuid + " - will retry next sweep", e);
            }
        }
    }
}
