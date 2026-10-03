package com.forjacraft.vipmanager.kit;

import java.util.List;

/**
 * One free kit (novato/claim/fazendeiro/comida) - no VIP tier involved. Either
 * {@code oneTime} (claimable exactly once, ever - cooldownSeconds is ignored)
 * or a plain recurring cooldown, matching PlayerKits2's own {@code one_time}
 * vs {@code cooldown} semantics for the kits it used to deliver.
 */
public record FreeKitDefinition(String id, boolean oneTime, long cooldownSeconds, List<KitItemSpec> items) {

    /** Namespaced so it can't collide with a VIP kit's id (e.g. "barao_mensal") in the shared kit_claims table. */
    public String storageId() {
        return "free_" + id;
    }
}
