package com.forjacraft.vipmanager.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Opens native Bedrock forms for Floodgate players and runs the clicked button's
 * commands as that player. Everything is a safe no-op when Floodgate is absent.
 */
public final class BedrockMenuService {

    private final Plugin plugin;
    private final BedrockMenuRegistry registry;
    private final boolean floodgateAvailable;

    public BedrockMenuService(Plugin plugin, BedrockMenuRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.floodgateAvailable = Bukkit.getPluginManager().getPlugin("floodgate") != null;
    }

    public boolean isFloodgateAvailable() {
        return floodgateAvailable;
    }

    public boolean isBedrock(UUID uuid) {
        if (!floodgateAvailable) {
            return false;
        }
        try {
            return FloodgateBridge.isBedrock(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    public enum OpenResult {OPENED, UNKNOWN_MENU, NOT_BEDROCK}

    public OpenResult open(Player player, String menuId) {
        Optional<BedrockMenu> maybeMenu = registry.get(menuId);
        if (maybeMenu.isEmpty()) {
            return OpenResult.UNKNOWN_MENU;
        }
        if (!isBedrock(player.getUniqueId())) {
            return OpenResult.NOT_BEDROCK;
        }
        BedrockMenu menu = maybeMenu.get();
        try {
            FloodgateBridge.send(player.getUniqueId(), BedrockText.toBedrock(menu.title()),
                    BedrockText.toBedrock(menu.content()), menu.buttons(),
                    clicked -> runButton(player, menu, clicked));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to send Bedrock menu '" + menuId + "' to " + player.getName(), t);
        }
        return OpenResult.OPENED;
    }

    private void runButton(Player player, BedrockMenu menu, int index) {
        if (index < 0 || index >= menu.buttons().size()) {
            return;
        }
        BedrockMenu.Button button = menu.buttons().get(index);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (button.isPurchase() && !purchase(player, button)) {
                return;
            }
            for (String command : button.commands()) {
                String clean = command.startsWith("/") ? command.substring(1) : command;
                Bukkit.dispatchCommand(player, clean);
            }
        });
    }

    /**
     * Same guards as the DeluxeMenus chest: refuse if already owned, take the Cash first and only
     * then run the grant commands, so a failed charge can never hand out the item for free.
     */
    private boolean purchase(Player player, BedrockMenu.Button button) {
        if (button.unlessPermission() != null && player.hasPermission(button.unlessPermission())) {
            player.sendMessage(ChatColor.YELLOW + "Você já possui este item.");
            return false;
        }
        if (button.price() > 0) {
            switch (PlayerPointsBridge.take(player.getUniqueId(), button.price())) {
                case INSUFFICIENT -> {
                    player.sendMessage(ChatColor.WHITE + "Você " + ChatColor.RED + "não " + ChatColor.WHITE
                            + "tem moedas de " + ChatColor.GREEN + "Cash " + ChatColor.WHITE
                            + "suficiente para adquirir esse produto.");
                    return false;
                }
                case UNAVAILABLE -> {
                    player.sendMessage(ChatColor.RED + "A loja de Cash está indisponível no momento.");
                    plugin.getLogger().warning("Purchase refused for " + player.getName()
                            + ": PlayerPoints is not available.");
                    return false;
                }
                case OK -> {
                }
            }
        }
        for (String grant : button.grantCommands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), grant.replace("%player%", player.getName()));
        }
        plugin.getLogger().info(player.getName() + " bought '" + ChatColor.stripColor(
                BedrockText.toBedrock(button.text())) + "' for " + button.price() + " Cash (Bedrock).");
        if (button.successMessage() != null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', button.successMessage()));
        }
        return true;
    }
}
