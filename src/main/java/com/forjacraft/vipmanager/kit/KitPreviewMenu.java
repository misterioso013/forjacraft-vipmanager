package com.forjacraft.vipmanager.kit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * A read-only inventory showing a kit's real, resolved items (Nexo pieces
 * render with their actual skin, since these are the real ItemStacks Nexo's
 * own API built - not a generic placeholder icon). Exists specifically to
 * replace PlayerKits2's preview screen, which has no way back to the
 * DeluxeMenus kitsvip menu once opened - the last slot here is a "Voltar"
 * button that reopens it via the same "dm open kitsvip" command DeluxeMenus
 * itself would run.
 */
public final class KitPreviewMenu implements InventoryHolder {

    private static final int BACK_BUTTON_SLOT_OFFSET_FROM_END = 1;

    private final Inventory inventory;
    private final String backCommand;

    private KitPreviewMenu(int size, String title, String backCommand) {
        this.inventory = Bukkit.createInventory(this, size, title);
        this.backCommand = backCommand;
    }

    /** VIP tier kit preview - always returns to the kitsvip DeluxeMenus screen. */
    public static KitPreviewMenu open(Player player, KitDefinition kit, List<ItemStack> items) {
        return open(player, previewTitle(kit), items, "dm open kitsvip");
    }

    public static KitPreviewMenu open(Player player, String title, List<ItemStack> items, String backCommand) {
        int size = roundUpToRowMultiple(items.size() + 9); // spare row for the back button
        KitPreviewMenu menu = new KitPreviewMenu(size, title, backCommand);

        for (ItemStack item : items) {
            menu.inventory.addItem(item.clone());
        }
        menu.inventory.setItem(size - BACK_BUTTON_SLOT_OFFSET_FROM_END, backButton());

        player.openInventory(menu.inventory);
        return menu;
    }

    String backCommand() {
        return backCommand;
    }

    private static String previewTitle(KitDefinition kit) {
        return ChatColor.translateAlternateColorCodes('&',
                "&8Prévia: &f" + kit.tier().toUpperCase() + " " + kit.period().toUpperCase());
    }

    private static ItemStack backButton() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&a&lVoltar"));
        meta.setLore(List.of(ChatColor.translateAlternateColorCodes('&', "&7Clique para voltar ao menu de kits")));
        item.setItemMeta(meta);
        return item;
    }

    static boolean isBackButtonSlot(Inventory inventory, int slot) {
        return slot == inventory.getSize() - BACK_BUTTON_SLOT_OFFSET_FROM_END;
    }

    private static int roundUpToRowMultiple(int minSize) {
        int rows = (int) Math.ceil(minSize / 9.0);
        rows = Math.min(Math.max(rows, 1), 6);
        return rows * 9;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
