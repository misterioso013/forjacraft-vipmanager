package com.forjacraft.vipmanager.integration;

import com.forjacraft.vipmanager.bedrock.BedrockMenuService;
import com.forjacraft.vipmanager.config.VipConfig;
import com.forjacraft.vipmanager.service.TierState;
import com.forjacraft.vipmanager.service.VipService;
import com.forjacraft.vipmanager.service.VipTimeMath;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * Registered only if PlaceholderAPI is present (see VipManagerPlugin#onEnable).
 * Lets DeluxeMenus/TAB consume VIP state with zero code changes on their side,
 * the same way they already consume %luckperms_prefix%.
 */
public final class VipPlaceholderExpansion extends PlaceholderExpansion {

    private final VipService vipService;
    private final VipConfig config;
    private final BedrockMenuService bedrock;

    public VipPlaceholderExpansion(VipService vipService, VipConfig config, BedrockMenuService bedrock) {
        this.vipService = vipService;
        this.config = config;
        this.bedrock = bedrock;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "vipmanager";
    }

    @Override
    public @NotNull String getAuthor() {
        return "misterioso013";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        // Answered before touching storage: DeluxeMenus asks this on every menu open.
        if (params.equals("is_bedrock")) {
            return String.valueOf(bedrock.isBedrock(player.getUniqueId()));
        }
        Map<String, TierState> balances = vipService.peekBalances(player.getUniqueId());
        long now = System.currentTimeMillis();

        Optional<Map.Entry<String, TierState>> active = balances.entrySet().stream()
                .filter(e -> e.getValue().active())
                .findFirst();

        switch (params) {
            case "active_tier":
                return active.map(Map.Entry::getKey).orElse(config.noTierPlaceholder());

            case "active_remaining":
                return active.map(e -> formatRemaining(e.getValue(), now)).orElse("0s");

            case "active_remaining_seconds":
                return active.map(e -> String.valueOf(VipTimeMath.remainingSeconds(e.getValue(), now))).orElse("0");

            case "has_any":
                boolean hasAny = balances.values().stream()
                        .anyMatch(s -> VipTimeMath.remainingSeconds(s, now) > 0);
                return String.valueOf(hasAny);

            default:
                return handleTierScoped(params, balances, now);
        }
    }

    /** Handles owned_<tier>_remaining, owned_<tier>_remaining_seconds, owned_<tier>_active */
    private String handleTierScoped(String params, Map<String, TierState> balances, long now) {
        if (!params.startsWith("owned_")) {
            return null;
        }
        String remainder = params.substring("owned_".length());

        for (String suffix : new String[]{"_remaining_seconds", "_remaining", "_active"}) {
            if (remainder.endsWith(suffix)) {
                String tierId = remainder.substring(0, remainder.length() - suffix.length());
                if (!config.isKnownTier(tierId)) {
                    return null;
                }
                TierState state = balances.getOrDefault(tierId, TierState.EMPTY);
                return switch (suffix) {
                    case "_remaining_seconds" -> String.valueOf(VipTimeMath.remainingSeconds(state, now));
                    case "_remaining" -> formatRemaining(state, now);
                    case "_active" -> String.valueOf(state.active());
                    default -> null;
                };
            }
        }
        return null;
    }

    private String formatRemaining(TierState state, long now) {
        long seconds = VipTimeMath.remainingSeconds(state, now);
        return com.forjacraft.vipmanager.command.DurationParser.format(seconds);
    }
}
