package com.forjacraft.vipmanager.command;

import com.forjacraft.vipmanager.bedrock.BedrockMenuRegistry;
import com.forjacraft.vipmanager.bedrock.BedrockMenuService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /bedrockmenu <id> [player]} - opens the native Bedrock form for a menu.
 * Used by the DeluxeMenus redirect (a Bedrock player who hits a chest menu is
 * bounced here from a console {@code deny_commands} entry, so it needs no
 * per-player permission); also handy for admins testing a form.
 */
public final class BedrockMenuCommand implements CommandExecutor, TabCompleter {

    private final BedrockMenuService service;
    private final BedrockMenuRegistry registry;

    public BedrockMenuCommand(BedrockMenuService service, BedrockMenuRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Uso: /bedrockmenu <menu> [jogador]");
            return true;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("Jogador não encontrado: " + args[1]);
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("Do console, informe o jogador: /bedrockmenu <menu> <jogador>");
            return true;
        }

        switch (service.open(target, args[0].toLowerCase())) {
            case UNKNOWN_MENU -> sender.sendMessage("Menu Bedrock desconhecido: " + args[0]);
            case NOT_BEDROCK -> sender.sendMessage(target.getName() + " não é um jogador Bedrock.");
            case OPENED -> {
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String id : registry.ids()) {
                if (id.startsWith(prefix)) {
                    result.add(id);
                }
            }
        }
        return result;
    }
}
