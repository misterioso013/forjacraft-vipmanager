package com.forjacraft.vipmanager.kit;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Resolve-check-space-give, shared by {@link KitService} (VIP tier kits) and
 * {@link FreeKitService} (novato/claim/fazendeiro/comida) - both need the exact
 * same "don't lose half a kit to a full inventory" atomicity, just with
 * different access/cooldown rules upstream of this.
 */
final class KitDelivery {

    private KitDelivery() {
    }

    /** One resolved item plus where it should land - only meaningful for the {@code offhand} spec flag. */
    record ResolvedItem(ItemStack stack, boolean offhand) {
    }

    static List<ResolvedItem> resolveAll(KitItemResolver resolver, List<KitItemSpec> specs,
                                          String kitIdForLogging, UUID uuid, Logger logger) {
        List<ResolvedItem> resolved = new ArrayList<>();
        boolean offhandTaken = false;
        for (KitItemSpec spec : specs) {
            ItemStack stack = resolver.resolve(spec).orElse(null);
            if (stack == null) {
                logger.warning("Kit '" + kitIdForLogging + "': item spec " + spec + " did not resolve - skipped for " + uuid);
                continue;
            }
            boolean offhand = !offhandTaken && spec instanceof KitItemSpec.Vanilla vanilla && vanilla.offhand();
            if (offhand) {
                offhandTaken = true;
            }
            resolved.add(new ResolvedItem(stack, offhand));
        }
        return resolved;
    }

    /** Rough check: enough free slots to hold every non-offhand item, plus an empty offhand slot if one is needed. */
    static boolean hasSpaceFor(Player player, List<ResolvedItem> items) {
        boolean needsOffhand = items.stream().anyMatch(ResolvedItem::offhand);
        if (needsOffhand) {
            ItemStack currentOffhand = player.getInventory().getItemInOffHand();
            if (currentOffhand != null && !currentOffhand.getType().isAir()) {
                return false;
            }
        }
        long regularCount = items.stream().filter(i -> !i.offhand()).count();
        int freeSlots = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                freeSlots++;
            }
        }
        return freeSlots >= regularCount;
    }

    static void give(Player player, List<ResolvedItem> items) {
        for (ResolvedItem item : items) {
            if (item.offhand()) {
                player.getInventory().setItemInOffHand(item.stack());
            } else {
                player.getInventory().addItem(item.stack());
            }
        }
    }
}
