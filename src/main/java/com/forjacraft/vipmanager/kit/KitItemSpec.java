package com.forjacraft.vipmanager.kit;

import java.util.List;

/**
 * One entry in a kit's item list - either a plain vanilla item or a Nexo
 * custom item. Resolving this into an actual {@link org.bukkit.inventory.ItemStack}
 * is {@link KitItemResolver}'s job; this type is just the parsed config data.
 */
public sealed interface KitItemSpec {

    int amount();

    record Vanilla(
            String material,
            int amount,
            List<String> enchants,
            Integer fireworkPower,
            String potionEffect,
            boolean potionExtended,
            boolean potionUpgraded,
            String displayName,
            List<String> lore,
            Integer leatherColor,
            boolean offhand
    ) implements KitItemSpec {
    }

    record Nexo(String nexoId, int amount) implements KitItemSpec {
    }
}
