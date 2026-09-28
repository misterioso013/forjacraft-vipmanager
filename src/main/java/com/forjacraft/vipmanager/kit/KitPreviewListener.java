package com.forjacraft.vipmanager.kit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

/**
 * Makes {@link KitPreviewMenu} read-only and wires its "Voltar" button back to
 * the DeluxeMenus kitsvip screen via the same command DeluxeMenus itself uses
 * to open it - no DeluxeMenus API dependency needed, just dispatching its command.
 */
public final class KitPreviewListener implements Listener {

    private final Plugin plugin;
    private final String kitsMenuOpenCommand;

    public KitPreviewListener(Plugin plugin, String kitsMenuOpenCommand) {
        this.plugin = plugin;
        this.kitsMenuOpenCommand = kitsMenuOpenCommand;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof KitPreviewMenu)) {
            return;
        }
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        if (!KitPreviewMenu.isBackButtonSlot(event.getInventory(), event.getSlot())) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(player, kitsMenuOpenCommand));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof KitPreviewMenu) {
            event.setCancelled(true);
        }
    }
}
