package com.forjacraft.vipmanager.kit;

import java.util.List;

/**
 * One kit: a tier + period pair (e.g. "barao"/"mensal"), its cooldown, and its
 * item list. {@code kitId()} is the stable key used for cooldown storage and
 * commands, e.g. "barao_mensal".
 */
public record KitDefinition(String tier, String period, long cooldownSeconds, List<KitItemSpec> items) {

    public String kitId() {
        return tier + "_" + period;
    }
}
