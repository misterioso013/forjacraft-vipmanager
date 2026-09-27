package com.forjacraft.vipmanager.command;

import com.forjacraft.vipmanager.config.TierDefinition;
import com.forjacraft.vipmanager.config.VipConfig;
import com.forjacraft.vipmanager.integration.LuckPermsBridge;
import com.forjacraft.vipmanager.service.TierState;
import com.forjacraft.vipmanager.service.VipService;
import com.forjacraft.vipmanager.service.VipTimeMath;
import com.forjacraft.vipmanager.storage.AuditEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Admin/integration-facing /vipadmin - this is what Azuriom's purchase command (and future Cash purchases) would call. */
public final class VipAdminCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final VipService vipService;
    private final VipConfig config;
    private final LuckPermsBridge luckPerms;
    private final Logger logger;
    private final Runnable reloadConfig;

    public VipAdminCommand(Plugin plugin, VipService vipService, VipConfig config,
                            LuckPermsBridge luckPerms, Logger logger, Runnable reloadConfig) {
        this.plugin = plugin;
        this.vipService = vipService;
        this.config = config;
        this.luckPerms = luckPerms;
        this.logger = logger;
        this.reloadConfig = reloadConfig;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Uso: /vipadmin <add|remove|set|activate|info|migrate|reload> ...");
            return true;
        }

        String sub = args[0].toLowerCase();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                switch (sub) {
                    case "add" -> handleAdd(sender, args);
                    case "remove" -> handleRemove(sender, args);
                    case "set" -> handleSet(sender, args);
                    case "activate" -> handleActivate(sender, args);
                    case "info" -> handleInfo(sender, args);
                    case "migrate" -> handleMigrate(sender);
                    case "reload" -> handleReload(sender);
                    default -> sendToSender(sender, "Subcomando desconhecido: " + sub);
                }
            } catch (IllegalArgumentException e) {
                sendToSender(sender, "&cErro: " + e.getMessage());
            } catch (Exception e) {
                logger.severe("Erro inesperado em /vipadmin " + sub + ": " + e);
                sendToSender(sender, "&cErro inesperado - veja o console.");
            }
        });
        return true;
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sendToSender(sender, "&cUso: /vipadmin add <jogador> <tier> <duração>");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        String tierId = requireTier(args[2]);
        long seconds = DurationParser.parseSeconds(args[3]);

        vipService.addTime(uuid, tierId, seconds, AuditEvent.Source.ADMIN_CONSOLE, sender.getName());
        sendToSender(sender, "&aAdicionado &f" + DurationParser.format(seconds)
                + " &aao tier &f" + tierId + " &apara &f" + args[1] + "&a.");
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sendToSender(sender, "&cUso: /vipadmin remove <jogador> <tier> <duração|all>");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        String tierId = requireTier(args[2]);

        if (args[3].equalsIgnoreCase("all")) {
            vipService.adminRemove(uuid, tierId, null, sender.getName());
            sendToSender(sender, "&aTier &f" + tierId + " &aremovido completamente de &f" + args[1] + "&a.");
            return;
        }
        long seconds = DurationParser.parseSeconds(args[3]);
        vipService.adminRemove(uuid, tierId, seconds, sender.getName());
        sendToSender(sender, "&aRemovido &f" + DurationParser.format(seconds)
                + " &ado tier &f" + tierId + " &ade &f" + args[1] + "&a.");
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sendToSender(sender, "&cUso: /vipadmin set <jogador> <tier> <duração>");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        String tierId = requireTier(args[2]);
        long seconds = DurationParser.parseSeconds(args[3]);

        vipService.adminSet(uuid, tierId, seconds, sender.getName());
        sendToSender(sender, "&aSaldo do tier &f" + tierId + " &apara &f" + args[1]
                + " &adefinido em &f" + DurationParser.format(seconds) + "&a.");
    }

    private void handleActivate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendToSender(sender, "&cUso: /vipadmin activate <jogador> <tier>");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        String tierId = requireTier(args[2]);

        VipService.SwitchResult result = vipService.switchActive(uuid, tierId);
        sendToSender(sender, switch (result) {
            case SUCCESS -> "&aTier &f" + tierId + " &aativado pra &f" + args[1] + "&a.";
            case NOT_OWNED -> "&c" + args[1] + " &cnão tem saldo no tier &f" + tierId + "&c.";
            case ALREADY_ACTIVE -> "&f" + args[1] + " &cjá está com o tier &f" + tierId + " &cativo.";
        });
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendToSender(sender, "&cUso: /vipadmin info <jogador>");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        Map<String, TierState> balances = vipService.peekBalances(uuid);
        long now = System.currentTimeMillis();

        sendToSender(sender, "&8&m----&r &bVIP de " + args[1] + " &8&m----");
        boolean any = false;
        for (TierDefinition tier : config.tiersLowestFirst()) {
            TierState state = balances.get(tier.id());
            if (state == null) {
                continue;
            }
            long remaining = VipTimeMath.remainingSeconds(state, now);
            any = true;
            sendToSender(sender, (state.active() ? "&a[ATIVO] " : "&7[banco] ") + "&f" + tier.id()
                    + " &7- " + DurationParser.format(remaining));
        }
        if (!any) {
            sendToSender(sender, "&7Nenhum saldo de VIP registrado.");
        }
    }

    private void handleMigrate(CommandSender sender) {
        sendToSender(sender, "&eIniciando migração: removendo os grupos de VIP de todo mundo (sem conceder tempo banco)...");
        int totalRemoved = 0;

        for (TierDefinition tier : config.tiersLowestFirst()) {
            Set<UUID> holders = luckPerms.searchAllWithGroup(tier.luckPermsGroup()).join();
            for (UUID uuid : holders) {
                luckPerms.setActiveGroup(uuid, null).join();
                totalRemoved++;
            }
            sendToSender(sender, "&7- Tier &f" + tier.id() + "&7: " + holders.size() + " jogador(es) revertido(s) pro default.");
        }

        sendToSender(sender, "&aMigração concluída. Total de concessões removidas: &f" + totalRemoved
                + "&a. Nenhum saldo de tempo banco foi criado - todo VIP daqui pra frente entra via /vipadmin add.");
        logger.info("VIP migration executed by " + sender.getName() + " - " + totalRemoved + " permanent grants removed.");
    }

    private void handleReload(CommandSender sender) {
        reloadConfig.run();
        sendToSender(sender, "&aConfig recarregado.");
    }

    private String requireTier(String tierId) {
        String normalized = tierId.toLowerCase();
        if (!config.isKnownTier(normalized)) {
            throw new IllegalArgumentException("Tier desconhecido: " + tierId);
        }
        return normalized;
    }

    private UUID resolveUuid(String playerName) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        return offlinePlayer.getUniqueId();
    }

    private void sendToSender(CommandSender sender, String message) {
        String colored = org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(colored));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("add", "remove", "set", "activate", "info", "migrate", "reload"));
        } else if (args.length == 2 && List.of("add", "remove", "set", "activate", "info").contains(args[0].toLowerCase())) {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                options.add(player.getName());
            }
        } else if (args.length == 3 && List.of("add", "remove", "set", "activate").contains(args[0].toLowerCase())) {
            config.tiersLowestFirst().forEach(t -> options.add(t.id()));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("remove")) {
            options.add("all");
        }

        String prefix = args.length > 0 ? args[args.length - 1].toLowerCase() : "";
        List<String> filtered = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix)) {
                filtered.add(option);
            }
        }
        return filtered;
    }
}
