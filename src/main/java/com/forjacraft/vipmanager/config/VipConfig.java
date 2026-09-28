package com.forjacraft.vipmanager.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed wrapper over config.yml. Tiers are kept in an order-preserving map so
 * "rank" (used for fallback-tier selection) matches the order they're written
 * in config.yml, lowest first.
 *
 * <p>This object is reloaded IN PLACE ({@link #reload}) rather than replaced,
 * so every other class that was handed a reference to it at startup (the
 * service, the LuckPerms bridge, commands) automatically sees fresh values
 * after {@code /vipadmin reload} without needing to be re-wired.</p>
 */
public final class VipConfig {

    private volatile Map<String, TierDefinition> tiersById = Collections.emptyMap();
    private volatile List<TierDefinition> tiersByRank = Collections.emptyList();
    private volatile int sweepIntervalSeconds = 60;
    private volatile int switchCooldownSeconds = 60;
    private volatile String noTierPlaceholder = "none";
    private volatile String kitsMenuOpenCommand = "dm open kitsvip";
    private volatile Map<String, String> messages = Collections.emptyMap();

    public VipConfig(FileConfiguration file) {
        reload(file);
    }

    public void reload(FileConfiguration file) {
        Map<String, TierDefinition> byId = new LinkedHashMap<>();
        List<TierDefinition> byRank = new ArrayList<>();

        ConfigurationSection tiersSection = file.getConfigurationSection("tiers");
        if (tiersSection == null) {
            throw new IllegalStateException("config.yml is missing the 'tiers' section");
        }
        int rank = 0;
        for (String tierId : tiersSection.getKeys(false)) {
            ConfigurationSection tierSection = tiersSection.getConfigurationSection(tierId);
            if (tierSection == null) {
                continue;
            }
            TierDefinition def = new TierDefinition(
                    tierId,
                    rank++,
                    tierSection.getString("luckPermsGroup", tierId),
                    tierSection.getLong("claimBlockBonus", 0L),
                    tierSection.getString("displayName", tierId)
            );
            byId.put(tierId, def);
            byRank.add(def);
        }

        Map<String, String> newMessages = new LinkedHashMap<>();
        ConfigurationSection messagesSection = file.getConfigurationSection("messages");
        if (messagesSection != null) {
            for (String key : messagesSection.getKeys(false)) {
                newMessages.put(key, messagesSection.getString(key, ""));
            }
        }

        this.tiersById = Collections.unmodifiableMap(byId);
        this.tiersByRank = Collections.unmodifiableList(byRank);
        this.sweepIntervalSeconds = file.getInt("sweep.interval-seconds", 60);
        this.switchCooldownSeconds = file.getInt("switch.cooldown-seconds", 60);
        this.noTierPlaceholder = file.getString("no-tier-placeholder", "none");
        this.kitsMenuOpenCommand = file.getString("kits.menu-open-command", "dm open kitsvip");
        this.messages = Collections.unmodifiableMap(newMessages);
    }

    public Optional<TierDefinition> tier(String id) {
        return Optional.ofNullable(tiersById.get(id));
    }

    /** All configured tiers, lowest rank first. */
    public List<TierDefinition> tiersLowestFirst() {
        return tiersByRank;
    }

    /** All configured tiers, highest rank first - used for fallback selection. */
    public List<TierDefinition> tiersHighestFirst() {
        List<TierDefinition> reversed = new ArrayList<>(tiersByRank);
        Collections.reverse(reversed);
        return reversed;
    }

    public boolean isKnownTier(String id) {
        return tiersById.containsKey(id);
    }

    public int sweepIntervalSeconds() {
        return sweepIntervalSeconds;
    }

    public int switchCooldownSeconds() {
        return switchCooldownSeconds;
    }

    public String noTierPlaceholder() {
        return noTierPlaceholder;
    }

    public String kitsMenuOpenCommand() {
        return kitsMenuOpenCommand;
    }

    public String message(String key) {
        return messages.getOrDefault(key, key);
    }

    public String prefixedMessage(String key) {
        return message("prefix") + message(key);
    }
}
