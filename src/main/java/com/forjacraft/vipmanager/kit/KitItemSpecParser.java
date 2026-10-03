package com.forjacraft.vipmanager.kit;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Parses one YAML map entry (from a kit's {@code items} list) into a
 * {@link KitItemSpec}. Shared between {@link KitRegistry} (tier-based VIP
 * kits) and {@link FreeKitRegistry} (novato/claim/fazendeiro/comida) since
 * both use the exact same item schema.
 */
public final class KitItemSpecParser {

    private KitItemSpecParser() {
    }

    @SuppressWarnings("unchecked")
    public static KitItemSpec parse(Map<?, ?> raw, String context, Logger logger) {
        Map<String, Object> map = (Map<String, Object>) raw;
        int amount = map.containsKey("amount") ? ((Number) map.get("amount")).intValue() : 1;

        if (map.containsKey("nexo-id")) {
            return new KitItemSpec.Nexo((String) map.get("nexo-id"), amount);
        }
        if (map.containsKey("material")) {
            List<String> enchants = (List<String>) map.getOrDefault("enchants", List.of());
            Integer fireworkPower = map.containsKey("firework-power")
                    ? ((Number) map.get("firework-power")).intValue() : null;
            String potionEffect = (String) map.get("potion-effect");
            boolean extended = Boolean.TRUE.equals(map.get("extended"));
            boolean upgraded = Boolean.TRUE.equals(map.get("upgraded"));
            String displayName = (String) map.get("display-name");
            List<String> lore = (List<String>) map.get("lore");
            Integer leatherColor = map.containsKey("leather-color")
                    ? ((Number) map.get("leather-color")).intValue() : null;
            boolean offhand = Boolean.TRUE.equals(map.get("offhand"));
            return new KitItemSpec.Vanilla((String) map.get("material"), amount, enchants,
                    fireworkPower, potionEffect, extended, upgraded, displayName, lore, leatherColor, offhand);
        }
        logger.warning("Kit item with neither 'material' nor 'nexo-id' in " + context + " - skipping: " + raw);
        return null;
    }
}
