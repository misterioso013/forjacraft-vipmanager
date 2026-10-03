package com.forjacraft.vipmanager.kit;

import com.forjacraft.vipmanager.service.TierState;
import com.forjacraft.vipmanager.service.VipService;
import com.forjacraft.vipmanager.service.VipTimeMath;
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
 * Kit access (by the player's ACTIVE tier - EXACT match only, not "at or
 * above"), cooldown enforcement, and delivery. A player can only claim the
 * kit matching their own active tier - kits carry real, tier-differentiated
 * rewards (a distinct Aurorite color, escalating consumables) each on its own
 * independent cooldown, so letting a higher tier also claim every lower
 * tier's kit would let them stack all 4 tiers' rewards at once. This
 * deliberately does NOT mirror LuckPerms group inheritance (which still
 * applies for passive perks like homes/discounts) - kit access and
 * permission inheritance are unrelated on purpose.
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

    /** Whether the player's currently active tier is exactly this kit's tier. */
    public boolean hasAccess(UUID uuid, String tier) {
        Optional<String> activeTier = vipService.peekActiveTier(uuid);
        return activeTier.isPresent() && activeTier.get().equals(tier);
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
        Optional<TierState> cooldown = storage.getKitCooldown(uuid, kit.kitId());
        if (cooldown.isPresent() && cooldown.get().bankedSeconds() > 0) {
            TierState state = cooldown.get();
            // Defensive self-heal: hasAccess() above already proved this tier is active
            // right now, so if the stored cooldown still says inactive (a missed
            // transition, or old data), resume it before reconciling rather than
            // leaving it frozen forever.
            if (!state.active()) {
                state = VipTimeMath.activate(state, now);
            }
            VipTimeMath.ReconcileResult result = VipTimeMath.reconcile(state, now);
            if (!result.expired()) {
                storage.saveKitCooldown(uuid, kit.kitId(), result.state());
                return new ClaimOutcome(ClaimResult.ON_COOLDOWN, result.state().bankedSeconds());
            }
        }

        // Resolve all items up front (atomic - resolve failures for individual Nexo
        // items are logged but don't block the rest; only a genuine space shortage aborts).
        List<KitDelivery.ResolvedItem> resolved = KitDelivery.resolveAll(resolver, kit.items(), kit.kitId(), uuid, logger);

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        Player player = offlinePlayer.getPlayer();
        if (player == null || !player.isOnline()) {
            // Kits are claimed interactively via the GUI, so this should not happen in
            // practice, but fail safe rather than silently drop items into the void.
            return ClaimOutcome.of(ClaimResult.UNKNOWN_KIT);
        }

        if (!KitDelivery.hasSpaceFor(player, resolved)) {
            return ClaimOutcome.of(ClaimResult.NOT_ENOUGH_SPACE);
        }

        KitDelivery.give(player, resolved);
        // hasAccess() already proved this tier is active right now, so the fresh
        // cooldown starts out active/ticking immediately.
        storage.saveKitCooldown(uuid, kit.kitId(), TierState.activeSince(kit.cooldownSeconds(), now));
        return ClaimOutcome.of(ClaimResult.SUCCESS);
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
