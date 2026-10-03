package com.forjacraft.vipmanager.kit;

import com.forjacraft.vipmanager.service.TierState;
import com.forjacraft.vipmanager.service.VipService;
import com.forjacraft.vipmanager.service.VipTimeMath;
import com.forjacraft.vipmanager.storage.VipStorage;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Keeps each VIP tier kit's cooldown ticking only while its owning tier is the
 * active one - freezes it the instant the tier is switched away from or
 * expires, resumes it the instant the tier becomes active again. Wired as a
 * {@link VipService.TierActivationListener}.
 *
 * <p>Exists because the cooldown used to be plain wall-clock time since the
 * last claim, regardless of whether the tier was actually active - a player
 * owning two tiers could switch to a lower one for a few seconds once a month
 * just to harvest its kit, then switch back, never really spending that
 * tier's own banked time. Ticking the cooldown only while active closes that:
 * the kit only comes back once the tier has genuinely been active for the
 * full cooldown duration.</p>
 *
 * <p>Only touches kits that already have a cooldown row (i.e. have been
 * claimed at least once) - nothing to freeze or resume for a kit nobody has
 * claimed yet. Reuses {@link VipTimeMath} unchanged: here {@code TierState}'s
 * "banked seconds" means "cooldown seconds remaining" instead of "VIP balance
 * remaining", but the checkpoint/freeze/resume math is identical.</p>
 */
public final class KitCooldownSync implements VipService.TierActivationListener {

    private final VipStorage storage;
    private final Logger logger;

    public KitCooldownSync(VipStorage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
    }

    @Override
    public void onTierActivation(UUID uuid, String tierId, boolean active, long nowMillis) {
        for (String period : KitRegistry.PERIODS) {
            String kitId = tierId + "_" + period;
            try {
                syncOne(uuid, kitId, active, nowMillis);
            } catch (Exception e) {
                logger.warning("Failed to sync kit cooldown for " + uuid + "/" + kitId + ": " + e.getMessage());
            }
        }
    }

    private void syncOne(UUID uuid, String kitId, boolean active, long now) {
        Optional<TierState> maybeState = storage.getKitCooldown(uuid, kitId);
        if (maybeState.isEmpty()) {
            return;
        }
        TierState state = maybeState.get();

        if (active) {
            if (!state.active() && state.bankedSeconds() > 0) {
                storage.saveKitCooldown(uuid, kitId, VipTimeMath.activate(state, now));
            }
        } else if (state.active()) {
            storage.saveKitCooldown(uuid, kitId, VipTimeMath.deactivate(state, now).state());
        }
    }
}
