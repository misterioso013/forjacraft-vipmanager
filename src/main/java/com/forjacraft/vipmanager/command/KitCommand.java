package com.forjacraft.vipmanager.command;

import com.forjacraft.vipmanager.config.VipConfig;
import com.forjacraft.vipmanager.kit.FreeKitDefinition;
import com.forjacraft.vipmanager.kit.FreeKitRegistry;
import com.forjacraft.vipmanager.kit.FreeKitService;
import com.forjacraft.vipmanager.kit.KitPreviewMenu;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified {@code /kit} (alias {@code /kits}). EssentialsX also declares {@code /kit}, so
 * {@code plugin.yml} lists Essentials as a softdepend: VipManager then registers this command
 * second and wins the unprefixed name. All kits (VIP and free) live in this plugin.
 */
public final class KitCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final FreeKitService freeKitService;
    private final VipConfig config;

    public KitCommand(Plugin plugin, FreeKitService freeKitService, VipConfig config) {
        this.plugin = plugin;
        this.freeKitService = freeKitService;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            Bukkit.dispatchCommand(player, config.kitsGeneralMenuOpenCommand());
            return true;
        }

        if (args[0].equalsIgnoreCase("vip")) {
            Bukkit.dispatchCommand(player, config.kitsMenuOpenCommand());
            return true;
        }

        if (args[0].equalsIgnoreCase("preview")) {
            if (args.length < 2) {
                player.sendMessage(colored("&cUso: /kit preview <novato|claim|fazendeiro|comida>"));
                return true;
            }
            handlePreview(player, args[1].toLowerCase());
            return true;
        }

        handleClaim(player, args[0].toLowerCase());
        return true;
    }

    private void handleClaim(Player player, String kitId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            FreeKitService.ClaimOutcome outcome = freeKitService.claim(player.getUniqueId(), kitId);
            String message = switch (outcome.result()) {
                case SUCCESS -> config.message("kit-claimed");
                case UNKNOWN_KIT -> config.message("kit-unknown");
                case ALREADY_CLAIMED -> config.message("kit-already-claimed");
                case ON_COOLDOWN -> config.message("kit-on-cooldown")
                        .replace("%time%", DurationParser.format(outcome.cooldownRemainingSeconds()));
                case NOT_ENOUGH_SPACE -> config.message("kit-no-space");
            };
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(colored(message)));
        });
    }

    private void handlePreview(Player player, String kitId) {
        FreeKitRegistry registry = freeKitService.registry();
        if (!registry.isKnownKit(kitId)) {
            player.sendMessage(colored(config.message("kit-unknown")));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            java.util.Optional<FreeKitDefinition> maybeKit = registry.get(kitId);
            if (maybeKit.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(colored(config.message("kit-unknown"))));
                return;
            }
            List<ItemStack> items = freeKitService.previewItems(maybeKit.get());
            String title = colored("&8Prévia: &f" + kitId.toUpperCase());
            Bukkit.getScheduler().runTask(plugin, () ->
                    KitPreviewMenu.open(player, title, items, config.kitsGeneralMenuOpenCommand()));
        });
    }

    private String colored(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(FreeKitRegistry.IDS);
            options.add("vip");
            options.add("preview");
            return filterStartsWith(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("preview")) {
            return filterStartsWith(FreeKitRegistry.IDS, args[1]);
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
