package com.forjacraft.vipmanager.service;

import com.forjacraft.vipmanager.config.TierDefinition;
import com.forjacraft.vipmanager.config.VipConfig;
import com.forjacraft.vipmanager.integration.ClaimBlockBridge;
import com.forjacraft.vipmanager.integration.LuckPermsBridge;
import com.forjacraft.vipmanager.storage.AuditEvent;
import com.forjacraft.vipmanager.storage.VipStorage;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Core business logic. Storage-agnostic beyond the {@link VipStorage}
 * interface; only method here that must run on the main thread is anything
 * that ends up calling {@link ClaimBlockBridge}, which hops there itself.
 *
 * <p>All public methods here are expected to be called from an async context
 * (the sweep task, or a command handler that has already left the main
 * thread) except where noted - LuckPerms API calls and SQLite I/O are both
 * fine off the main thread; only the eventual {@code adjustclaimblocks}
 * console dispatch needs the main thread, which {@link ClaimBlockBridge}
 * handles internally.</p>
 */
public final class VipService {

    private final VipConfig config;
    private final VipStorage storage;
    private final LuckPermsBridge luckPerms;
    private final ClaimBlockBridge claimBlocks;
    private final Logger logger;

    public VipService(VipConfig config, VipStorage storage, LuckPermsBridge luckPerms,
                       ClaimBlockBridge claimBlocks, Logger logger) {
        this.config = config;
        this.storage = storage;
        this.luckPerms = luckPerms;
        this.claimBlocks = claimBlocks;
        this.logger = logger;
    }

    /** Read-only, non-persisting view of a player's current balances (for /vip status, placeholders). */
    public Map<String, TierState> peekBalances(UUID uuid) {
        return storage.loadBalances(uuid);
    }

    public long peekRemainingSeconds(UUID uuid, String tierId) {
        TierState state = storage.loadBalances(uuid).get(tierId);
        if (state == null) {
            return 0L;
        }
        return VipTimeMath.remainingSeconds(state, System.currentTimeMillis());
    }

    public Optional<String> peekActiveTier(UUID uuid) {
        return storage.loadBalances(uuid).entrySet().stream()
                .filter(e -> e.getValue().active())
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Adds time to a tier's banked balance (never overwrites). If the player
     * has no active tier at all, the funded tier is activated immediately.
     */
    public void addTime(UUID uuid, String tierId, long deltaSeconds, AuditEvent.Source source, String actor) {
        requireKnownTier(tierId);
        long now = System.currentTimeMillis();

        Map<String, TierState> balances = storage.loadBalances(uuid);
        TierState current = balances.getOrDefault(tierId, TierState.EMPTY);
        VipTimeMath.ReconcileResult result = VipTimeMath.addSeconds(current, deltaSeconds, now);
        storage.saveBalance(uuid, tierId, result.state());
        storage.appendAudit(new AuditEvent(uuid, tierId, AuditEvent.Type.GRANT, deltaSeconds,
                result.state().bankedSeconds(), source, actor, now));

        boolean hasAnyActive = balances.values().stream().anyMatch(TierState::active);
        if (!hasAnyActive) {
            activateInternal(uuid, tierId, balances, now);
        }
    }

    /**
     * Switches which owned tier is active. Fails silently-with-return-value
     * (rather than throwing) for the two user-facing rejection cases so
     * command handlers can show a clean message instead of a stack trace.
     */
    public enum SwitchResult {SUCCESS, NOT_OWNED, ALREADY_ACTIVE}

    public SwitchResult switchActive(UUID uuid, String tierId) {
        requireKnownTier(tierId);
        long now = System.currentTimeMillis();
        Map<String, TierState> balances = storage.loadBalances(uuid);

        TierState target = balances.get(tierId);
        long targetRemaining = target == null ? 0L : VipTimeMath.remainingSeconds(target, now);
        if (targetRemaining <= 0) {
            return SwitchResult.NOT_OWNED;
        }
        if (target.active()) {
            return SwitchResult.ALREADY_ACTIVE;
        }

        Optional<Map.Entry<String, TierState>> currentActive = balances.entrySet().stream()
                .filter(e -> e.getValue().active())
                .findFirst();

        long claimDelta = tierBonus(tierId);
        if (currentActive.isPresent()) {
            String oldTierId = currentActive.get().getKey();
            VipTimeMath.ReconcileResult deactivated = VipTimeMath.deactivate(currentActive.get().getValue(), now);
            storage.saveBalance(uuid, oldTierId, deactivated.state());
            claimDelta -= tierBonus(oldTierId);
        }

        TierState activated = VipTimeMath.activate(target, now);
        storage.saveBalance(uuid, tierId, activated);
        storage.appendAudit(new AuditEvent(uuid, tierId, AuditEvent.Type.SWITCH, null,
                activated.bankedSeconds(), AuditEvent.Source.ADMIN_INGAME, uuid.toString(), now));

        TierDefinition tierDef = config.tier(tierId).orElseThrow();
        awaitLuckPerms(luckPerms.setActiveGroup(uuid, tierDef.luckPermsGroup()), uuid);
        claimBlocks.adjust(uuid, claimDelta);

        return SwitchResult.SUCCESS;
    }

    /**
     * Reconciles a single player's active tier up to now. If it expired,
     * expires it (removing the LuckPerms group and activating a fallback
     * tier if one is owned). Called by the join listener (for the one player
     * who just connected) and the sweep task (for every player with any
     * active tier, online or not).
     */
    public void reconcilePlayer(UUID uuid) {
        long now = System.currentTimeMillis();
        Map<String, TierState> balances = storage.loadBalances(uuid);

        Optional<Map.Entry<String, TierState>> activeEntry = balances.entrySet().stream()
                .filter(e -> e.getValue().active())
                .findFirst();
        if (activeEntry.isEmpty()) {
            return;
        }

        String activeTierId = activeEntry.get().getKey();
        TierState activeState = activeEntry.get().getValue();
        VipTimeMath.ReconcileResult result = VipTimeMath.reconcile(activeState, now);

        if (!result.expired()) {
            storage.saveBalance(uuid, activeTierId, result.state());
            return;
        }

        // Expired - persist the zeroed-out state, then look for a fallback.
        storage.saveBalance(uuid, activeTierId, result.state());
        long expiredBonus = tierBonus(activeTierId);

        Optional<TierDefinition> fallback = config.tiersHighestFirst().stream()
                .filter(t -> !t.id().equals(activeTierId))
                .filter(t -> {
                    TierState s = balances.get(t.id());
                    return s != null && VipTimeMath.remainingSeconds(s, now) > 0;
                })
                .findFirst();

        if (fallback.isPresent()) {
            TierDefinition fallbackTier = fallback.get();
            TierState fallbackState = VipTimeMath.activate(balances.get(fallbackTier.id()), now);
            storage.saveBalance(uuid, fallbackTier.id(), fallbackState);
            storage.appendAudit(new AuditEvent(uuid, activeTierId, AuditEvent.Type.EXPIRE_FALLBACK, null,
                    0L, AuditEvent.Source.SYSTEM_SWEEP, "SYSTEM", now));

            awaitLuckPerms(luckPerms.setActiveGroup(uuid, fallbackTier.luckPermsGroup()), uuid);
            claimBlocks.adjust(uuid, tierBonus(fallbackTier.id()) - expiredBonus);
            logger.info("VIP tier '" + activeTierId + "' expired for " + uuid + " - fell back to '" + fallbackTier.id() + "'");
        } else {
            storage.appendAudit(new AuditEvent(uuid, activeTierId, AuditEvent.Type.EXPIRE, null,
                    0L, AuditEvent.Source.SYSTEM_SWEEP, "SYSTEM", now));

            awaitLuckPerms(luckPerms.setActiveGroup(uuid, null), uuid);
            claimBlocks.adjust(uuid, -expiredBonus);
            logger.info("VIP tier '" + activeTierId + "' expired for " + uuid + " - no fallback owned, reverted to default");
        }
    }

    /** Every UUID that currently has any tier marked active - what the sweep task iterates. */
    public Set<UUID> allPlayersWithActiveTier() {
        return storage.allPlayersWithActiveTier();
    }

    /** Admin: absolute override of a tier's balance. Does not touch active/inactive state. */
    public void adminSet(UUID uuid, String tierId, long seconds, String actor) {
        requireKnownTier(tierId);
        Map<String, TierState> balances = storage.loadBalances(uuid);
        TierState current = balances.getOrDefault(tierId, TierState.EMPTY);
        TierState updated = current.active()
                ? TierState.activeSince(seconds, System.currentTimeMillis())
                : TierState.inactive(seconds);
        storage.saveBalance(uuid, tierId, updated);
        storage.appendAudit(new AuditEvent(uuid, tierId, AuditEvent.Type.ADMIN_SET, null,
                seconds, AuditEvent.Source.ADMIN_CONSOLE, actor, System.currentTimeMillis()));
    }

    /** Admin: removes time from (or entirely wipes, if resulting balance <= 0) a tier's balance. */
    public void adminRemove(UUID uuid, String tierId, Long secondsOrNullForAll, String actor) {
        requireKnownTier(tierId);
        long now = System.currentTimeMillis();

        if (secondsOrNullForAll == null) {
            storage.deleteBalance(uuid, tierId);
            storage.appendAudit(new AuditEvent(uuid, tierId, AuditEvent.Type.ADMIN_REMOVE, null,
                    0L, AuditEvent.Source.ADMIN_CONSOLE, actor, now));
            return;
        }

        Map<String, TierState> balances = storage.loadBalances(uuid);
        TierState current = balances.getOrDefault(tierId, TierState.EMPTY);
        long remaining = VipTimeMath.remainingSeconds(current, now) - secondsOrNullForAll;

        TierState updated = remaining > 0
                ? (current.active() ? TierState.activeSince(remaining, now) : TierState.inactive(remaining))
                : TierState.inactive(0L);
        storage.saveBalance(uuid, tierId, updated);
        storage.appendAudit(new AuditEvent(uuid, tierId, AuditEvent.Type.ADMIN_REMOVE, -secondsOrNullForAll,
                updated.bankedSeconds(), AuditEvent.Source.ADMIN_CONSOLE, actor, now));
    }

    private void activateInternal(UUID uuid, String tierId, Map<String, TierState> balances, long now) {
        TierState state = balances.getOrDefault(tierId, TierState.EMPTY);
        // Balance was just credited by the caller before calling this - re-read to get the fresh value.
        TierState fresh = storage.loadBalances(uuid).getOrDefault(tierId, state);
        TierState activated = VipTimeMath.activate(fresh, now);
        storage.saveBalance(uuid, tierId, activated);

        TierDefinition tierDef = config.tier(tierId).orElseThrow();
        awaitLuckPerms(luckPerms.setActiveGroup(uuid, tierDef.luckPermsGroup()), uuid);
        claimBlocks.adjust(uuid, tierBonus(tierId));
    }

    /**
     * Blocks (on the caller's already-off-main-thread context) until a
     * LuckPerms mutation completes, logging rather than throwing on failure -
     * the balance in our own storage is already correct either way, so a
     * transient LuckPerms error shouldn't crash the caller; it will be
     * corrected on the next join-time reconciliation (see the join listener).
     */
    private void awaitLuckPerms(java.util.concurrent.CompletableFuture<Void> future, UUID uuid) {
        try {
            future.join();
        } catch (Exception e) {
            logger.warning("LuckPerms update failed for " + uuid + " (will self-correct on next join): " + e.getMessage());
        }
    }

    private long tierBonus(String tierId) {
        return config.tier(tierId).map(TierDefinition::claimBlockBonus).orElse(0L);
    }

    private void requireKnownTier(String tierId) {
        if (!config.isKnownTier(tierId)) {
            throw new IllegalArgumentException("Unknown tier '" + tierId + "'");
        }
    }
}
