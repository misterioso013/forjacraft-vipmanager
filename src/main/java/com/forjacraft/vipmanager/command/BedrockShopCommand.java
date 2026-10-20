package com.forjacraft.vipmanager.command;

import com.forjacraft.vipmanager.bedrock.BedrockShopService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;

/** {@code /bedrockshop <categoria> [pagina]}: opens the native Bedrock shop (chest shop for Java players). */
public final class BedrockShopCommand implements CommandExecutor {

    private final BedrockShopService service;

    public BedrockShopCommand(BedrockShopService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Somente jogadores.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Uso: /" + label + " <categoria> [pagina]");
            return true;
        }
        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                // keep page 1
            }
        }
        service.open(player, args[0].toLowerCase(), page);
        return true;
    }
}
