package com.forjacraft.vipmanager.integration;

import com.forjacraft.vipmanager.config.TierDefinition;
import com.forjacraft.vipmanager.config.VipConfig;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Wraps the LuckPerms API. The applied tier group is always a permanent
 * (non-expiring) inheritance node - VipManager itself is the sole timing
 * authority (see VipService); LuckPerms is only ever told "this player has
 * group X" or "this player no longer has any of the 4 tier groups", never
 * given a duration of its own to count down.
 */
public final class LuckPermsBridge {

    private final LuckPerms luckPerms;
    private final VipConfig config;

    public LuckPermsBridge(VipConfig config) {
        this.luckPerms = LuckPermsProvider.get();
        this.config = config;
    }

    /**
     * Removes every configured tier group from the user, then adds
     * {@code newTierGroup} if non-null. Safe to call off the main thread.
     */
    public CompletableFuture<Void> setActiveGroup(UUID uuid, String newTierGroupOrNull) {
        return luckPerms.getUserManager().loadUser(uuid).thenCompose(user -> {
            for (TierDefinition tier : config.tiersLowestFirst()) {
                user.data().remove(InheritanceNode.builder(tier.luckPermsGroup()).build());
            }
            if (newTierGroupOrNull != null) {
                user.data().add(InheritanceNode.builder(newTierGroupOrNull).build());
            }
            return luckPerms.getUserManager().saveUser(user);
        });
    }

    /**
     * Which of the configured tier LuckPerms groups (by tier id, not group
     * name) this user currently has, according to LuckPerms itself. Normally
     * 0 or 1; more than 1 indicates a manual /lp mistake or a mid-operation
     * crash and should be logged loudly by the caller.
     */
    public CompletableFuture<Set<String>> currentTierIds(UUID uuid) {
        return luckPerms.getUserManager().loadUser(uuid).thenApply(user -> {
            Set<String> groupNames = user.getNodes(NodeType.INHERITANCE).stream()
                    .map(InheritanceNode::getGroupName)
                    .collect(Collectors.toSet());
            return config.tiersLowestFirst().stream()
                    .filter(tier -> groupNames.contains(tier.luckPermsGroup()))
                    .map(TierDefinition::id)
                    .collect(Collectors.toSet());
        });
    }

    /**
     * All UUIDs that currently have a permanent inheritance node to
     * {@code groupName} in LuckPerms' backing storage - including offline
     * players never loaded this session. Used only by the one-time migration.
     */
    public CompletableFuture<Set<UUID>> searchAllWithGroup(String groupName) {
        InheritanceNode node = InheritanceNode.builder(groupName).build();
        return luckPerms.getUserManager()
                .searchAll(NodeMatcher.key(node))
                .thenApply(results -> results.keySet());
    }

    /** Loads a user and returns them for callers that need more than this bridge exposes. */
    public CompletableFuture<User> loadUser(UUID uuid) {
        return luckPerms.getUserManager().loadUser(uuid);
    }

    public CompletableFuture<Void> saveUser(User user) {
        return luckPerms.getUserManager().saveUser(user);
    }
}
