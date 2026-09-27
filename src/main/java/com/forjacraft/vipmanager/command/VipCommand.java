package com.forjacraft.vipmanager.command;

import com.forjacraft.vipmanager.config.TierDefinition;
import com.forjacraft.vipmanager.config.VipConfig;
import com.forjacraft.vipmanager.service.TierState;
import com.forjacraft.vipmanager.service.VipService;
import com.forjacraft.vipmanager.service.VipTimeMath;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Player-facing /vip and /vip switch <tier>. */
public final class VipCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final VipService vipService;
    private final VipConfig config;
    private final Map<UUID, Long> lastSwitchAtMillis = new ConcurrentHashMap<>();

    public VipCommand(Plugin plugin, VipService vipService, VipConfig config) {
        this.plugin = plugin;
        this.vipService = vipService;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendStatus(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("switch")) {
            if (args.length < 2) {
                player.sendMessage(colored("&cUso: /vip switch <tier>"));
                return true;
            }
            handleSwitch(player, args[1].toLowerCase());
            return true;
        }

        sendStatus(player);
        return true;
    }

    private void sendStatus(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, TierState> balances = vipService.peekBalances(player.getUniqueId());
            long now = System.currentTimeMillis();
            List<String> lines = new ArrayList<>();
            lines.add(colored("&8&m----------&r &bSeus VIPs &8&m----------"));

            boolean any = false;
            for (TierDefinition tier : config.tiersLowestFirst()) {
                TierState state = balances.get(tier.id());
                long remaining = state == null ? 0 : VipTimeMath.remainingSeconds(state, now);
                if (remaining <= 0) {
                    continue;
                }
                any = true;
                boolean active = state.active();
                String marker = active ? "&a[ATIVO] " : "&7[banco] ";
                lines.add(colored(marker + tier.displayName() + " &7- " + DurationParser.format(remaining)));
            }

            if (!any) {
                lines.add(colored(config.message("no-tier-owned")));
            }
            lines.add(colored("&8&m--------------------------------"));

            Bukkit.getScheduler().runTask(plugin, () -> lines.forEach(player::sendMessage));
        });
    }

    private void handleSwitch(Player player, String tierId) {
        if (!config.isKnownTier(tierId)) {
            player.sendMessage(colored(config.message("not-owned").replace("%tier%", tierId)));
            return;
        }

        if (!player.hasPermission("vipmanager.bypass.switchcooldown")) {
            long lastSwitch = lastSwitchAtMillis.getOrDefault(player.getUniqueId(), 0L);
            long elapsedSeconds = (System.currentTimeMillis() - lastSwitch) / 1000L;
            long cooldown = config.switchCooldownSeconds();
            if (elapsedSeconds < cooldown) {
                player.sendMessage(colored(config.message("switch-cooldown")
                        .replace("%seconds%", String.valueOf(cooldown - elapsedSeconds))));
                return;
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            VipService.SwitchResult result = vipService.switchActive(player.getUniqueId(), tierId);
            String message = switch (result) {
                case SUCCESS -> {
                    lastSwitchAtMillis.put(player.getUniqueId(), System.currentTimeMillis());
                    yield config.message("switch-success").replace("%tier%", tierId);
                }
                case NOT_OWNED -> config.message("not-owned").replace("%tier%", tierId);
                case ALREADY_ACTIVE -> config.message("already-active").replace("%tier%", tierId);
            };
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(colored(message)));
        });
    }

    private String colored(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("switch", "help"));
            return filterStartsWith(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("switch")) {
            List<String> tierIds = config.tiersLowestFirst().stream().map(TierDefinition::id).toList();
            return filterStartsWith(tierIds, args[1]);
        }
        return List.of();
    }

    private List<String> filterStartsWith(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
