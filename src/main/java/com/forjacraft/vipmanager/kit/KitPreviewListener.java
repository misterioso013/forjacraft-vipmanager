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
 * whichever DeluxeMenus screen that particular preview should return to
 * (kitsvip for a VIP tier kit, the general kits menu for a free kit) - each
 * {@link KitPreviewMenu} instance carries its own back command, so one
 * listener instance serves both. No DeluxeMenus API dependency needed, just
 * dispatching the same command DeluxeMenus itself uses to open the menu.
 */
public final class KitPreviewListener implements Listener {

    private final Plugin plugin;

    public KitPreviewListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof KitPreviewMenu menu)) {
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
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(player, menu.backCommand()));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof KitPreviewMenu) {
            event.setCancelled(true);
        }
    }
}
