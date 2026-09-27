package com.forjacraft.vipmanager.config;

/**
 * A configured VIP tier. {@code rank} is its position in the configured order
 * (0 = lowest), used to pick a fallback tier when the active one expires.
 */
public record TierDefinition(String id, int rank, String luckPermsGroup, long claimBlockBonus, String displayName) {
}
