package com.forjacraft.vipmanager.kit;

import com.nexomc.nexo.api.NexoItems;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Resolves a {@link KitItemSpec} into a real {@link ItemStack}. The Nexo path
 * goes through {@code NexoItems.optionalItemFromId} + {@code ItemBuilder.build()} -
 * a real, public, static Nexo API (confirmed by decompiling nexo-1.28.jar), not a
 * console command. Vanilla items are built directly with Bukkit's own API.
 */
public final class KitItemResolver {

    private final Logger logger;

    public KitItemResolver(Logger logger) {
        this.logger = logger;
    }

    /** Empty if a Nexo item id doesn't resolve (Nexo absent, or the id was renamed/removed) - callers must handle this. */
    public Optional<ItemStack> resolve(KitItemSpec spec) {
        if (spec instanceof KitItemSpec.Nexo nexo) {
            return resolveNexo(nexo);
        }
        if (spec instanceof KitItemSpec.Vanilla vanilla) {
            return Optional.of(resolveVanilla(vanilla));
        }
        throw new IllegalStateException("Unknown KitItemSpec type: " + spec);
    }

    private Optional<ItemStack> resolveNexo(KitItemSpec.Nexo nexo) {
        try {
            return NexoItems.optionalItemFromId(nexo.nexoId())
                    .map(builder -> {
                        ItemStack stack = builder.build();
                        stack.setAmount(nexo.amount());
                        return stack;
                    });
        } catch (Throwable t) {
            logger.warning("Failed to resolve Nexo item '" + nexo.nexoId() + "': " + t.getMessage());
            return Optional.empty();
        }
    }

    private ItemStack resolveVanilla(KitItemSpec.Vanilla vanilla) {
        Material material = Material.valueOf(vanilla.material().toUpperCase(Locale.ROOT));
        ItemStack stack = new ItemStack(material, vanilla.amount());

        boolean hasDisplayName = vanilla.displayName() != null && !vanilla.displayName().isBlank();
        boolean hasLore = vanilla.lore() != null && !vanilla.lore().isEmpty();
        if (hasDisplayName || hasLore) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (hasDisplayName) {
                    meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', vanilla.displayName()));
                }
                if (hasLore) {
                    meta.setLore(vanilla.lore().stream()
                            .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                            .toList());
                }
                stack.setItemMeta(meta);
            }
        }
        if (vanilla.leatherColor() != null && stack.getItemMeta() instanceof LeatherArmorMeta leatherMeta) {
            leatherMeta.setColor(Color.fromRGB(vanilla.leatherColor()));
            stack.setItemMeta(leatherMeta);
        }
        if (vanilla.enchants() != null && !vanilla.enchants().isEmpty()) {
            applyEnchants(stack, vanilla.enchants());
        }
        if (vanilla.fireworkPower() != null && stack.getItemMeta() instanceof FireworkMeta fireworkMeta) {
            fireworkMeta.setPower(vanilla.fireworkPower());
            stack.setItemMeta(fireworkMeta);
        }
        if (vanilla.potionEffect() != null && stack.getItemMeta() instanceof PotionMeta potionMeta) {
            applyPotion(potionMeta, vanilla);
            stack.setItemMeta(potionMeta);
        }
        return stack;
    }

    /** Entries look like "UNBREAKING;2" - matches the format already used in the PlayerKits2 kit YAMLs. */
    private void applyEnchants(ItemStack stack, java.util.List<String> entries) {
        for (String entry : entries) {
            String[] parts = entry.split(";");
            String enchantName = parts[0].toUpperCase(Locale.ROOT);
            int level = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
            Enchantment enchantment = Enchantment.getByName(enchantName);
            if (enchantment == null) {
                logger.warning("Unknown enchant '" + enchantName + "' in kit item spec - skipping");
                continue;
            }
            stack.addUnsafeEnchantment(enchantment, level);
        }
    }

    private void applyPotion(PotionMeta meta, KitItemSpec.Vanilla vanilla) {
        String base = vanilla.potionEffect().toUpperCase(Locale.ROOT);
        String prefix = vanilla.potionUpgraded() ? "STRONG_" : vanilla.potionExtended() ? "LONG_" : "";
        String constantName = prefix + base;
        try {
            PotionType type = PotionType.valueOf(constantName);
            meta.setBasePotionType(type);
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown potion type '" + constantName + "' (base=" + base
                    + ", extended=" + vanilla.potionExtended() + ", upgraded=" + vanilla.potionUpgraded()
                    + ") - falling back to plain '" + base + "'");
            try {
                meta.setBasePotionType(PotionType.valueOf(base));
            } catch (IllegalArgumentException e2) {
                logger.warning("Base potion type '" + base + "' also unknown - leaving potion unconfigured");
            }
        }
    }
}
