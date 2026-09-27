package com.forjacraft.vipmanager.storage;

import java.util.UUID;

/**
 * One row of the append-only audit trail. Money-adjacent feature, worth the
 * cheap insurance for chargeback/support disputes.
 */
public record AuditEvent(
        UUID uuid,
        String tier,
        Type eventType,
        Long deltaSeconds,
        long resultingSeconds,
        Source source,
        String actor,
        long createdAtMillis
) {
    public enum Type {
        GRANT, SWITCH, EXPIRE, EXPIRE_FALLBACK, ADMIN_SET, ADMIN_REMOVE, MIGRATE
    }

    public enum Source {
        AZURIOM, ADMIN_CONSOLE, ADMIN_INGAME, CASH_PURCHASE, MIGRATION, SYSTEM_SWEEP
    }
}
