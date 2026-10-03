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
            FloodgateBridge.send(player.getUniqueId(), color(menu.title()), color(menu.content()), menu.buttons(),
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
            for (String command : button.commands()) {
                String clean = command.startsWith("/") ? command.substring(1) : command;
                Bukkit.dispatchCommand(player, clean);
            }
        });
    }

    private String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }
}
