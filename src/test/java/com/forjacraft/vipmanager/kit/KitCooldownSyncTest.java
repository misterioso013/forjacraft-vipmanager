package com.forjacraft.vipmanager.kit;

import com.forjacraft.vipmanager.service.TierState;
import com.forjacraft.vipmanager.storage.AuditEvent;
import com.forjacraft.vipmanager.storage.VipStorage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitCooldownSyncTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Logger LOGGER = Logger.getLogger("KitCooldownSyncTest");

    /** Minimal in-memory VipStorage - only the kit-cooldown methods matter for this test. */
    private static final class FakeStorage implements VipStorage {
        final Map<String, TierState> cooldowns = new HashMap<>();

        @Override
        public void init() {
        }

        @Override
        public void close() {
        }

        @Override
        public Map<String, TierState> loadBalances(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveBalance(UUID uuid, String tier, TierState state) {
        }

        @Override
        public void deleteBalance(UUID uuid, String tier) {
        }

        @Override
        public Set<UUID> allPlayersWithActiveTier() {
            return Set.of();
        }

        @Override
        public void appendAudit(AuditEvent event) {
        }

        @Override
        public Optional<Long> getKitClaimTime(UUID uuid, String kitId) {
            return Optional.empty();
        }

        @Override
        public void recordKitClaim(UUID uuid, String kitId, long claimedAtMillis) {
        }

        @Override
        public Optional<TierState> getKitCooldown(UUID uuid, String kitId) {
            return Optional.ofNullable(cooldowns.get(uuid + "/" + kitId));
        }

        @Override
        public void saveKitCooldown(UUID uuid, String kitId, TierState state) {
            cooldowns.put(uuid + "/" + kitId, state);
        }
    }

    @Test
    void doesNothingWhenKitNeverClaimed() {
        FakeStorage storage = new FakeStorage();
        KitCooldownSync sync = new KitCooldownSync(storage, LOGGER);

        sync.onTierActivation(PLAYER, "barao", true, 1000L);
        sync.onTierActivation(PLAYER, "barao", false, 2000L);

        assertTrue(storage.cooldowns.isEmpty());
    }

    @Test
    void freezesRemainingSecondsWhenTierGoesInactive() {
        FakeStorage storage = new FakeStorage();
        long now = 1_000_000L;
        // Claimed with a 30-day cooldown, active since "now".
        storage.saveKitCooldown(PLAYER, "barao_mensal", TierState.activeSince(2_592_000L, now));
        KitCooldownSync sync = new KitCooldownSync(storage, LOGGER);

        long tenDaysLater = now + 10L * 86_400L * 1000L;
        sync.onTierActivation(PLAYER, "barao", false, tenDaysLater);

        TierState frozen = storage.cooldowns.get(PLAYER + "/barao_mensal");
        assertFalse(frozen.active());
        assertEquals(2_592_000L - 10L * 86_400L, frozen.bankedSeconds());
    }

    @Test
    void resumesTickingWhenTierGoesActiveAgain() {
        FakeStorage storage = new FakeStorage();
        long now = 1_000_000L;
        // Frozen with 20 days remaining (e.g. was deactivated after 10 of its 30 days).
        storage.saveKitCooldown(PLAYER, "barao_mensal", TierState.inactive(20L * 86_400L));
        KitCooldownSync sync = new KitCooldownSync(storage, LOGGER);

        sync.onTierActivation(PLAYER, "barao", true, now);

        TierState resumed = storage.cooldowns.get(PLAYER + "/barao_mensal");
        assertTrue(resumed.active());
        assertEquals(20L * 86_400L, resumed.bankedSeconds());
        assertEquals(now, resumed.activeSince());
    }

    @Test
    void doesNotResumeAnAlreadyExpiredCooldown() {
        FakeStorage storage = new FakeStorage();
        // Already at zero (eligible to claim) - resuming would throw in VipTimeMath.activate,
        // and there's nothing meaningful to resume anyway.
        storage.saveKitCooldown(PLAYER, "barao_mensal", TierState.inactive(0L));
        KitCooldownSync sync = new KitCooldownSync(storage, LOGGER);

        sync.onTierActivation(PLAYER, "barao", true, 5_000L);

        TierState state = storage.cooldowns.get(PLAYER + "/barao_mensal");
        assertFalse(state.active());
        assertEquals(0L, state.bankedSeconds());
    }

    @Test
    void freezingAnAlreadyExpiredCooldownLeavesItAtZero() {
        FakeStorage storage = new FakeStorage();
        long now = 1_000_000L;
        // Active with only 5 seconds left...
        storage.saveKitCooldown(PLAYER, "barao_mensal", TierState.activeSince(5L, now));
        KitCooldownSync sync = new KitCooldownSync(storage, LOGGER);

        // ...but a full day passes before the tier is actually deactivated.
        long oneDayLater = now + 86_400L * 1000L;
        sync.onTierActivation(PLAYER, "barao", false, oneDayLater);

        TierState state = storage.cooldowns.get(PLAYER + "/barao_mensal");
        assertFalse(state.active());
        assertEquals(0L, state.bankedSeconds());
    }

    @Test
    void onlyTouchesTheThreeKitsOfTheGivenTier() {
        FakeStorage storage = new FakeStorage();
        long now = 1_000_000L;
        storage.saveKitCooldown(PLAYER, "barao_mensal", TierState.activeSince(100L, now));
        storage.saveKitCooldown(PLAYER, "rei_mensal", TierState.activeSince(100L, now));
        KitCooldownSync sync = new KitCooldownSync(storage, LOGGER);

        sync.onTierActivation(PLAYER, "barao", false, now + 10_000L);

        assertFalse(storage.cooldowns.get(PLAYER + "/barao_mensal").active());
        assertTrue(storage.cooldowns.get(PLAYER + "/rei_mensal").active());
    }
}
