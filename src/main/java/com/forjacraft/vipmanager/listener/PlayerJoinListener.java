package com.forjacraft.vipmanager.listener;

import com.forjacraft.vipmanager.config.VipConfig;
import com.forjacraft.vipmanager.integration.LuckPermsBridge;
import com.forjacraft.vipmanager.service.VipService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * On join: reconcile this one player's active tier immediately. This is what
 * makes expiry correct across full server downtime - the sweep task can't run
 * while the process itself is down, so the first join after a restart is the
 * actual moment that catch-up happens.
 *
 * <p>Also cross-checks the plugin's recorded active tier against LuckPerms'
 * actual current state: if they disagree (a raw /lp command bypassed the
 * plugin, or a crash left things mid-operation), the plugin's banked-time
 * state is treated as authoritative and LuckPerms is corrected, with a loud
 * console warning either way.</p>
 */
public final class PlayerJoinListener implements Listener {

    private final Plugin plugin;
    private final VipService vipService;
    private final LuckPermsBridge luckPerms;
    private final VipConfig config;
    private final Logger logger;

    public PlayerJoinListener(Plugin plugin, VipService vipService, LuckPermsBridge luckPerms,
                               VipConfig config, Logger logger) {
        this.plugin = plugin;
        this.vipService = vipService;
        this.luckPerms = luckPerms;
        this.config = config;
        this.logger = logger;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> reconcile(uuid));
    }

    private void reconcile(UUID uuid) {
        vipService.reconcilePlayer(uuid);

        Optional<String> pluginActiveTier = vipService.peekActiveTier(uuid);
        Set<String> luckPermsTierIds = luckPerms.currentTierIds(uuid).join();

        boolean pluginSaysActive = pluginActiveTier.isPresent();
        boolean luckPermsAgrees = pluginSaysActive
                ? luckPermsTierIds.size() == 1 && luckPermsTierIds.contains(pluginActiveTier.get())
                : luckPermsTierIds.isEmpty();

        if (luckPermsAgrees) {
            return;
        }

        logger.warning("VIP state mismatch for " + uuid + ": plugin says active=" + pluginActiveTier
                + ", LuckPerms has tier groups=" + luckPermsTierIds + ". Correcting LuckPerms to match the plugin"
                + " (the plugin's banked-time storage is authoritative).");

        if (luckPermsTierIds.size() > 1) {
            logger.warning("Player " + uuid + " had MORE THAN ONE tier group applied in LuckPerms "
                    + luckPermsTierIds + " - this should never happen going forward and suggests a manual /lp mistake.");
        }

        String targetGroup = pluginActiveTier.flatMap(config::tier)
                .map(com.forjacraft.vipmanager.config.TierDefinition::luckPermsGroup)
                .orElse(null);
        luckPerms.setActiveGroup(uuid, targetGroup).join();
    }
}
