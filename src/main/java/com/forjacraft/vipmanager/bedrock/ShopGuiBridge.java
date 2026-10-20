package com.forjacraft.vipmanager.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to ShopGUIPlus through reflection, so VipManager builds without that (paid) jar and
 * keeps working when it is absent. Every price, discount, stock rule and the transaction itself
 * stay inside ShopGUIPlus: this class only reads the catalogue and calls its own buy/sell handlers.
 */
final class ShopGuiBridge {

    enum Kind {ITEM, LINK, OTHER}

    /** One entry of a ShopGUIPlus category. {@code raw} is the ShopItem object, for later calls. */
    record Entry(String id, Kind kind, ItemStack item, String linkShopId, int page, int slot, Object raw) {
    }

    private static final String API = "net.brcdev.shopgui.ShopGuiPlusApi";

    private final Plugin shopPlugin;
    private final ClassLoader loader;

    private ShopGuiBridge(Plugin shopPlugin) {
        this.shopPlugin = shopPlugin;
        this.loader = shopPlugin.getClass().getClassLoader();
    }

    static ShopGuiBridge create() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("ShopGUIPlus");
        return plugin != null && plugin.isEnabled() ? new ShopGuiBridge(plugin) : null;
    }

    Object shop(String id) throws ReflectiveOperationException {
        return Refl.invokeStatic(Class.forName(API, true, loader), "getShop", id);
    }

    boolean hasAccess(Object shop, Player player) throws ReflectiveOperationException {
        return (Boolean) Refl.invoke(shop, "hasAccess", player);
    }

    List<Entry> entries(Object shop) throws ReflectiveOperationException {
        List<?> raw = (List<?>) Refl.invoke(shop, "getShopItems");
        List<Entry> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            String type = String.valueOf(Refl.invoke(item, "getType"));
            Kind kind = switch (type) {
                case "ITEM" -> Kind.ITEM;
                case "SHOP_LINK" -> Kind.LINK;
                default -> Kind.OTHER;
            };
            String link = null;
            if (kind == Kind.LINK) {
                Object shopLink = Refl.invoke(item, "getShopLink");
                link = shopLink == null ? null : String.valueOf(Refl.invoke(shopLink, "getShopId"));
            }
            ItemStack stack = kind == Kind.ITEM ? (ItemStack) Refl.invoke(item, "getItem") : null;
            out.add(new Entry(String.valueOf(Refl.invoke(item, "getId")), kind, stack, link,
                    (Integer) Refl.invoke(item, "getPage"), (Integer) Refl.invoke(item, "getSlot"), item));
        }
        return out;
    }

    /** Price for {@code amount} items for this player (VIP discounts included); negative when not for sale. */
    double buyPrice(Entry entry, Player player, int amount) throws ReflectiveOperationException {
        return (Double) Refl.invoke(entry.raw(), "getBuyPriceForAmount", player, amount);
    }

    double sellPrice(Entry entry, Player player, int amount) throws ReflectiveOperationException {
        return (Double) Refl.invoke(entry.raw(), "getSellPriceForAmount", player, amount);
    }

    double balance(Object shop, Player player) throws ReflectiveOperationException {
        Object provider = Refl.invoke(shop, "getEconomyProvider");
        return (Double) Refl.invoke(provider, "getBalance", player);
    }

    String money(Object shop, double value) {
        try {
            Class<?> chat = Class.forName("net.brcdev.shopgui.util.ChatUtils", true, loader);
            String formatted = (String) Refl.invokeStatic(chat, "formatCurrencyString", shop, value);
            return org.bukkit.ChatColor.stripColor(formatted);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return String.format("$%.2f", value);
        }
    }

    /** @return the ShopTransactionResultType name, e.g. SUCCESS or FAILURE_NO_MONEY. */
    String buy(Player player, Entry entry, int amount) throws ReflectiveOperationException {
        Object result = Refl.invoke(manager(), "handleItemBuy", player, entry.raw(), amount, false);
        return String.valueOf(Refl.invoke(result, "getResult"));
    }

    String sell(Player player, Entry entry, int amount) throws ReflectiveOperationException {
        Object result = Refl.invoke(manager(), "handleItemSell", player, entry.raw(), amount, false, true);
        return String.valueOf(Refl.invoke(result, "getResult"));
    }

    String sellAll(Player player, Entry entry) throws ReflectiveOperationException {
        Object result = Refl.invoke(manager(), "handleItemSellAll", player, entry.raw(), false);
        return String.valueOf(Refl.invoke(result, "getResult"));
    }

    void openChestShop(Player player, String shopId) throws ReflectiveOperationException {
        Refl.invokeStatic(Class.forName(API, true, loader), "openShop", player, shopId, 1);
    }

    private Object manager() throws ReflectiveOperationException {
        Object plugin = Refl.invokeStatic(Class.forName(API, true, loader), "getPlugin");
        return Refl.invoke(plugin, "getShopManager");
    }

    Plugin plugin() {
        return shopPlugin;
    }

    /** Minimal reflection helpers: match a public method by name and argument count/compatible types. */
    static final class Refl {
        private Refl() {
        }

        static Object invoke(Object target, String name, Object... args) throws ReflectiveOperationException {
            return call(target.getClass(), target, name, args);
        }

        static Object invokeStatic(Class<?> type, String name, Object... args) throws ReflectiveOperationException {
            return call(type, null, name, args);
        }

        private static Object call(Class<?> type, Object target, String name, Object[] args)
                throws ReflectiveOperationException {
            for (Method m : type.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length && compatible(m.getParameterTypes(), args)) {
                    return m.invoke(target, args);
                }
            }
            throw new NoSuchMethodException(type.getName() + "#" + name);
        }

        private static boolean compatible(Class<?>[] params, Object[] args) {
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                Object a = args[i];
                if (p.isPrimitive()) {
                    if (a == null || !box(p).isInstance(a)) {
                        return false;
                    }
                } else if (a != null && !p.isInstance(a)) {
                    return false;
                }
            }
            return true;
        }

        private static Class<?> box(Class<?> p) {
            if (p == int.class) return Integer.class;
            if (p == double.class) return Double.class;
            if (p == boolean.class) return Boolean.class;
            if (p == long.class) return Long.class;
            return p;
        }
    }
}
