package com.forjacraft.vipmanager.kit;

import com.forjacraft.vipmanager.service.VipService;
import com.forjacraft.vipmanager.storage.VipStorage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Kit access (by the player's ACTIVE tier rank - matches how LuckPerms group
 * inheritance already works: only the active tier's benefits apply, and it
 * inherits everything at or below its own rank), cooldown enforcement, and
 * delivery. Replaces PlayerKits2 for the 12 tier-based VIP kits; the free
 * kits (novato/claim/fazendeiro) are untouched and stay on PlayerKits2.
 */
public final class KitService {

    private final KitRegistry registry;
    private final KitItemResolver resolver;
    private final VipService vipService;
    private final VipStorage storage;
    private final Logger logger;

    public KitService(KitRegistry registry, KitItemResolver resolver, VipService vipService,
                       VipStorage storage, Logger logger) {
        this.registry = registry;
        this.resolver = resolver;
        this.vipService = vipService;
        this.storage = storage;
        this.logger = logger;
    }

    public enum ClaimResult {
        SUCCESS, UNKNOWN_KIT, TIER_TOO_LOW, ON_COOLDOWN, NOT_ENOUGH_SPACE
    }

    public record ClaimOutcome(ClaimResult result, long cooldownRemainingSeconds) {
        static ClaimOutcome of(ClaimResult result) {
            return new ClaimOutcome(result, 0L);
        }
    }

    /** Whether the player's currently active tier is this kit's tier or higher. */
    public boolean hasAccess(UUID uuid, String tier) {
        Optional<String> activeTier = vipService.peekActiveTier(uuid);
        if (activeTier.isEmpty()) {
            return false;
        }
        return registry.tierRank(activeTier.get()) >= registry.tierRank(tier);
    }

    public ClaimOutcome claim(UUID uuid, String tier, String period) {
        Optional<KitDefinition> maybeKit = registry.get(tier, period);
        if (maybeKit.isEmpty()) {
            return ClaimOutcome.of(ClaimResult.UNKNOWN_KIT);
        }
        KitDefinition kit = maybeKit.get();

        if (!hasAccess(uuid, tier)) {
            return ClaimOutcome.of(ClaimResult.TIER_TOO_LOW);
        }

        long now = System.currentTimeMillis();
        Optional<Long> lastClaim = storage.getKitClaimTime(uuid, kit.kitId());
        if (lastClaim.isPresent()) {
            long elapsedSeconds = (now - lastClaim.get()) / 1000L;
            long remaining = kit.cooldownSeconds() - elapsedSeconds;
            if (remaining > 0) {
                return new ClaimOutcome(ClaimResult.ON_COOLDOWN, remaining);
            }
        }

        // Resolve all items up front (atomic - resolve failures for individual Nexo
        // items are logged but don't block the rest; only a genuine space shortage aborts).
        List<ItemStack> resolved = new ArrayList<>();
        for (KitItemSpec spec : kit.items()) {
            resolver.resolve(spec).ifPresentOrElse(
                    resolved::add,
                    () -> logger.warning("Kit '" + kit.kitId() + "': item spec " + spec + " did not resolve - skipped for " + uuid)
            );
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        Player player = offlinePlayer.getPlayer();
        if (player == null || !player.isOnline()) {
            // Kits are claimed interactively via the GUI, so this should not happen in
            // practice, but fail safe rather than silently drop items into the void.
            return ClaimOutcome.of(ClaimResult.UNKNOWN_KIT);
        }

        if (!hasSpaceFor(player, resolved)) {
            return ClaimOutcome.of(ClaimResult.NOT_ENOUGH_SPACE);
        }

        for (ItemStack stack : resolved) {
            player.getInventory().addItem(stack);
        }
        storage.recordKitClaim(uuid, kit.kitId(), now);
        return ClaimOutcome.of(ClaimResult.SUCCESS);
    }

    /** Rough check: enough free slots to hold every item, one slot each (doesn't try to stack into partial slots). */
    private boolean hasSpaceFor(Player player, List<ItemStack> items) {
        int freeSlots = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                freeSlots++;
            }
        }
        return freeSlots >= items.size();
    }

    /** For the preview GUI: the resolved item list without claiming anything or touching cooldowns. */
    public List<ItemStack> previewItems(KitDefinition kit) {
        List<ItemStack> resolved = new ArrayList<>();
        for (KitItemSpec spec : kit.items()) {
            resolver.resolve(spec).ifPresent(resolved::add);
        }
        return resolved;
    }

    public KitRegistry registry() {
        return registry;
    }
}
