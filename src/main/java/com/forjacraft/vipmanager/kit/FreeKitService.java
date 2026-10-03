package com.forjacraft.vipmanager.kit;

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
 * Claim handling for the free kits (novato/claim/fazendeiro/comida) - no tier
 * rank check (everyone has access), and two flavors of "can I claim again":
 * a plain recurring cooldown (claim, comida) or claimable exactly once ever
 * (novato, fazendeiro), matching PlayerKits2's {@code one_time} semantics.
 */
public final class FreeKitService {

    private final FreeKitRegistry registry;
    private final KitItemResolver resolver;
    private final VipStorage storage;
    private final Logger logger;

    public FreeKitService(FreeKitRegistry registry, KitItemResolver resolver, VipStorage storage, Logger logger) {
        this.registry = registry;
        this.resolver = resolver;
        this.storage = storage;
        this.logger = logger;
    }

    public enum ClaimResult {
        SUCCESS, UNKNOWN_KIT, ALREADY_CLAIMED, ON_COOLDOWN, NOT_ENOUGH_SPACE
    }

    public record ClaimOutcome(ClaimResult result, long cooldownRemainingSeconds) {
        static ClaimOutcome of(ClaimResult result) {
            return new ClaimOutcome(result, 0L);
        }
    }

    public ClaimOutcome claim(UUID uuid, String kitId) {
        Optional<FreeKitDefinition> maybeKit = registry.get(kitId);
        if (maybeKit.isEmpty()) {
            return ClaimOutcome.of(ClaimResult.UNKNOWN_KIT);
        }
        FreeKitDefinition kit = maybeKit.get();

        long now = System.currentTimeMillis();
        Optional<Long> lastClaim = storage.getKitClaimTime(uuid, kit.storageId());
        if (lastClaim.isPresent()) {
            if (kit.oneTime()) {
                return ClaimOutcome.of(ClaimResult.ALREADY_CLAIMED);
            }
            long elapsedSeconds = (now - lastClaim.get()) / 1000L;
            long remaining = kit.cooldownSeconds() - elapsedSeconds;
            if (remaining > 0) {
                return new ClaimOutcome(ClaimResult.ON_COOLDOWN, remaining);
            }
        }

        List<KitDelivery.ResolvedItem> resolved = KitDelivery.resolveAll(resolver, kit.items(), kit.storageId(), uuid, logger);

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        Player player = offlinePlayer.getPlayer();
        if (player == null || !player.isOnline()) {
            return ClaimOutcome.of(ClaimResult.UNKNOWN_KIT);
        }

        if (!KitDelivery.hasSpaceFor(player, resolved)) {
            return ClaimOutcome.of(ClaimResult.NOT_ENOUGH_SPACE);
        }

        KitDelivery.give(player, resolved);
        storage.recordKitClaim(uuid, kit.storageId(), now);
        return ClaimOutcome.of(ClaimResult.SUCCESS);
    }

    /** For the preview GUI: the resolved item list without claiming anything or touching cooldowns. */
    public List<ItemStack> previewItems(FreeKitDefinition kit) {
        List<ItemStack> resolved = new ArrayList<>();
        for (KitItemSpec spec : kit.items()) {
            resolver.resolve(spec).ifPresent(resolved::add);
        }
        return resolved;
    }

    public FreeKitRegistry registry() {
        return registry;
    }
}
