package com.forjacraft.vipmanager.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * A native Bedrock shop (forms) on top of ShopGUIPlus. The chest shop draws everything with
 * glyph titles and invisible buttons, which is unusable on a phone: no names, no prices.
 * Here every item is a text button with its name and price, and an item opens a screen with
 * "Comprar 1/16/64" and "Vender" buttons. Prices, VIP discounts and the transaction itself
 * come from ShopGUIPlus ({@link ShopGuiBridge}).
 */
public final class BedrockShopService {

    private final Plugin plugin;
    private final BedrockMenuService menus;
    private final ShopGuiBridge bridge;
    private final ItemNames names;
    private volatile Settings settings;

    private record Settings(int pageSize, Map<String, String> labels, Map<String, String> parents,
                            List<Integer> buyAmounts, List<Integer> sellAmounts) {
    }

    public BedrockShopService(Plugin plugin, BedrockMenuService menus) {
        this.plugin = plugin;
        this.menus = menus;
        this.bridge = ShopGuiBridge.create();
        File geyser = new File(plugin.getDataFolder().getParentFile(), "Geyser-Spigot/locales/pt_br.json");
        this.names = new ItemNames(geyser, plugin.getLogger());
        reload();
    }

    public boolean isAvailable() {
        return bridge != null && menus.isFloodgateAvailable();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "bedrock-shop.yml");
        if (!file.exists()) {
            plugin.saveResource("bedrock-shop.yml", false);
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        this.settings = new Settings(
                Math.max(5, yaml.getInt("page-size", 20)),
                section(yaml, "labels"), section(yaml, "parents"),
                amounts(yaml.getIntegerList("buy-amounts")), amounts(yaml.getIntegerList("sell-amounts")));
    }

    private static Map<String, String> section(FileConfiguration yaml, String key) {
        Map<String, String> map = new HashMap<>();
        var section = yaml.getConfigurationSection(key);
        if (section != null) {
            for (String k : section.getKeys(false)) {
                map.put(k, String.valueOf(section.get(k)));
            }
        }
        return map;
    }

    private static List<Integer> amounts(List<Integer> configured) {
        List<Integer> out = configured.stream().filter(n -> n != null && n > 0 && n <= 2304).toList();
        return out.isEmpty() ? List.of(1, 16, 64) : out;
    }

    // ------------------------------------------------------------------ category screen

    /** Opens a category for a Bedrock player; other players get the regular chest shop. */
    public void open(Player player, String shopId, int page) {
        if (bridge == null) {
            player.sendMessage(ChatColor.RED + "A loja está indisponível no momento.");
            return;
        }
        try {
            if (!menus.isBedrock(player.getUniqueId())) {
                bridge.openChestShop(player, shopId);
                return;
            }
            Object shop = bridge.shop(shopId);
            if (shop == null) {
                player.sendMessage(ChatColor.RED + "Categoria não encontrada.");
                return;
            }
            String blocked = accessProblem(player, shop);
            if (blocked != null) {
                player.sendMessage(ChatColor.RED + blocked);
                return;
            }
            showCategory(player, shopId, shop, page);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(player, "open " + shopId, e);
        }
    }

    private void showCategory(Player player, String shopId, Object shop, int requestedPage)
            throws ReflectiveOperationException {
        Settings s = settings;
        List<ShopGuiBridge.Entry> all = bridge.entries(shop);
        List<ShopGuiBridge.Entry> links = all.stream()
                .filter(e -> e.kind() == ShopGuiBridge.Kind.LINK && e.linkShopId() != null
                        && !e.linkShopId().equals(shopId))
                .toList();
        List<ShopGuiBridge.Entry> items = all.stream().filter(e -> e.kind() == ShopGuiBridge.Kind.ITEM)
                .sorted(Comparator.comparingInt(ShopGuiBridge.Entry::page).thenComparingInt(ShopGuiBridge.Entry::slot))
                .toList();

        int pages = Math.max(1, (items.size() + s.pageSize() - 1) / s.pageSize());
        int page = Math.min(Math.max(1, requestedPage), pages);
        List<ShopGuiBridge.Entry> slice = items.subList((page - 1) * s.pageSize(),
                Math.min(items.size(), page * s.pageSize()));

        List<BedrockMenu.Button> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (page == 1) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ShopGuiBridge.Entry link : links) {
                if (!seen.add(link.linkShopId())) {
                    continue;
                }
                buttons.add(button("&1&l» " + label(link.linkShopId()) + "\n&r&8abrir categoria"));
                String target = link.linkShopId();
                actions.add(() -> open(player, target, 1));
            }
        }
        for (ShopGuiBridge.Entry entry : slice) {
            double buy = bridge.buyPrice(entry, player, 1);
            double sell = bridge.sellPrice(entry, player, 1);
            int qty = entry.item() == null ? 1 : Math.max(1, entry.item().getAmount());
            buttons.add(button("&l" + names.name(entry.item()) + (qty > 1 ? " &8x" + qty : "") + "\n&r"
                    + priceLine(shop, buy, sell)));
            actions.add(() -> openItem(player, shopId, entry.id(), null));
        }
        if (page > 1) {
            buttons.add(button("&l« Página anterior"));
            int target = page - 1;
            actions.add(() -> open(player, shopId, target));
        }
        if (page < pages) {
            buttons.add(button("&l» Próxima página"));
            int target = page + 1;
            actions.add(() -> open(player, shopId, target));
        }
        String parent = s.parents().get(shopId);
        buttons.add(button("&8« Voltar"));
        actions.add(() -> {
            if (parent != null) {
                open(player, parent, 1);
            } else {
                Bukkit.dispatchCommand(player, "loja");
            }
        });

        String title = label(shopId) + (pages > 1 ? " (" + page + "/" + pages + ")" : "");
        String content = "&7Seu saldo: &a" + bridge.money(shop, bridge.balance(shop, player))
                + "\n&7Toque em um item para ver o preço e comprar ou vender.";
        send(player, title, content, buttons, actions);
    }

    // ------------------------------------------------------------------ item screen

    void openItem(Player player, String shopId, String itemId, String status) {
        try {
            Object shop = bridge.shop(shopId);
            Optional<ShopGuiBridge.Entry> found = shop == null ? Optional.empty() : bridge.entries(shop).stream()
                    .filter(e -> e.kind() == ShopGuiBridge.Kind.ITEM && e.id().equals(itemId)).findFirst();
            if (found.isEmpty()) {
                open(player, shopId, 1);
                return;
            }
            String blocked = accessProblem(player, shop);
            if (blocked != null) {
                player.sendMessage(ChatColor.RED + blocked);
                return;
            }
            ShopGuiBridge.Entry entry = found.get();
            Settings s = settings;
            String itemName = names.name(entry.item());
            double buy1 = bridge.buyPrice(entry, player, 1);
            double sell1 = bridge.sellPrice(entry, player, 1);

            List<BedrockMenu.Button> buttons = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            if (buy1 > 0) {
                for (int amount : s.buyAmounts()) {
                    double total = bridge.buyPrice(entry, player, amount);
                    buttons.add(button("&2&lComprar " + amount + "\n&r&8por " + bridge.money(shop, total)));
                    actions.add(() -> transact(player, shopId, entry, itemName, amount, true));
                }
            }
            if (sell1 > 0) {
                for (int amount : s.sellAmounts()) {
                    double total = bridge.sellPrice(entry, player, amount);
                    buttons.add(button("&6&lVender " + amount + "\n&r&8você recebe " + bridge.money(shop, total)));
                    actions.add(() -> transact(player, shopId, entry, itemName, amount, false));
                }
                buttons.add(button("&6&lVender tudo\n&r&8todos os que você tiver"));
                actions.add(() -> sellAll(player, shopId, entry, itemName));
            }
            buttons.add(button("&8« Voltar"));
            actions.add(() -> open(player, shopId, 1));

            StringBuilder content = new StringBuilder();
            if (status != null) {
                content.append(status).append("\n\n");
            }
            content.append("&7Seu saldo: &a").append(bridge.money(shop, bridge.balance(shop, player)));
            content.append("\n&7Compra (1 un.): ")
                    .append(buy1 > 0 ? "&a" + bridge.money(shop, buy1) : "&8não está à venda");
            content.append("\n&7Venda (1 un.): ")
                    .append(sell1 > 0 ? "&6" + bridge.money(shop, sell1) : "&8não é comprado");
            send(player, itemName, content.toString(), buttons, actions);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(player, "item " + shopId + "/" + itemId, e);
        }
    }

    private void transact(Player player, String shopId, ShopGuiBridge.Entry entry, String itemName, int amount,
                          boolean buying) {
        try {
            Object shop = bridge.shop(shopId);
            String blocked = accessProblem(player, shop);
            if (blocked != null) {
                player.sendMessage(ChatColor.RED + blocked);
                return;
            }
            double total = buying ? bridge.buyPrice(entry, player, amount) : bridge.sellPrice(entry, player, amount);
            String result = buying ? bridge.buy(player, entry, amount) : bridge.sell(player, entry, amount);
            String status = result.equals("SUCCESS")
                    ? (buying ? "&a✔ Comprou &f" : "&a✔ Vendeu &f") + amount + "x " + itemName + " &apor &f"
                    + bridge.money(shop, total)
                    : "&c✘ " + explain(result);
            openItem(player, shopId, entry.id(), status);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(player, "transaction " + shopId + "/" + entry.id(), e);
        }
    }

    private void sellAll(Player player, String shopId, ShopGuiBridge.Entry entry, String itemName) {
        try {
            Object shop = bridge.shop(shopId);
            String blocked = accessProblem(player, shop);
            if (blocked != null) {
                player.sendMessage(ChatColor.RED + blocked);
                return;
            }
            String result = bridge.sellAll(player, entry);
            String status = result.equals("SUCCESS")
                    ? "&a✔ Vendeu todos os seus &f" + itemName : "&c✘ " + explain(result);
            openItem(player, shopId, entry.id(), status);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(player, "sell all " + shopId + "/" + entry.id(), e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Same restrictions the chest shop applies: gamemode and world blocklists from the ShopGUIPlus config. */
    private String accessProblem(Player player, Object shop) throws ReflectiveOperationException {
        if (!bridge.hasAccess(shop, player)) {
            return "Você não tem acesso a esta categoria.";
        }
        File file = new File(bridge.plugin().getDataFolder(), "config.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        GameMode mode = player.getGameMode();
        if (!player.hasPermission("shopguiplus.bypassgamemode")
                && cfg.getStringList("disableShopsInGamemodes").stream().anyMatch(g -> g.equalsIgnoreCase(mode.name()))) {
            return "Você não pode usar a loja no modo " + mode.name().toLowerCase() + ".";
        }
        String world = player.getWorld().getName();
        if (!player.hasPermission("shopguiplus.bypassworld")
                && cfg.getStringList("disableShopsInWorlds").stream().anyMatch(w -> w.equalsIgnoreCase(world))) {
            return "A loja não está disponível neste mundo.";
        }
        return null;
    }

    private String priceLine(Object shop, double buy, double sell) {
        StringBuilder sb = new StringBuilder();
        if (buy > 0) {
            sb.append("&2Compra: &a").append(bridge.money(shop, buy));
        }
        if (sell > 0) {
            if (sb.length() > 0) {
                sb.append("  &8|  ");
            }
            sb.append("&6Venda: &e").append(bridge.money(shop, sell));
        }
        return sb.length() == 0 ? "&8indisponível" : sb.toString();
    }

    private static String explain(String result) {
        return switch (result) {
            case "FAILURE_NO_MONEY" -> "Você não tem dinheiro suficiente.";
            case "FAILURE_NO_ITEMS" -> "Você não tem esse item para vender.";
            case "FAILURE_FULL_INVENTORY" -> "Seu inventário está cheio.";
            case "FAILURE_CANCELLED" -> "A operação foi cancelada.";
            case "FAILURE_PERMISSION_DISABLED" -> "Você não tem permissão para este item.";
            default -> "Não foi possível concluir (" + result + ").";
        };
    }

    private String label(String shopId) {
        String label = settings.labels().get(shopId);
        return label != null ? label : ItemNames.humanize(shopId);
    }

    private static BedrockMenu.Button button(String text) {
        return new BedrockMenu.Button(text, null, List.of());
    }

    private void send(Player player, String title, String content, List<BedrockMenu.Button> buttons,
                      List<Runnable> actions) {
        try {
            FloodgateBridge.send(player.getUniqueId(), BedrockText.toBedrock(title), BedrockText.toBedrock(content),
                    buttons, index -> {
                        if (index >= 0 && index < actions.size()) {
                            Bukkit.getScheduler().runTask(plugin, actions.get(index));
                        }
                    });
        } catch (Throwable t) {
            fail(player, "send form", t);
        }
    }

    private void fail(Player player, String what, Throwable t) {
        plugin.getLogger().log(Level.WARNING, "Bedrock shop failed (" + what + ") for " + player.getName(), t);
        player.sendMessage(ChatColor.RED + "Não foi possível abrir a loja agora. Tente de novo em instantes.");
    }
}
