package com.forjacraft.vipmanager.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Charges Cash (PlayerPoints) for a Bedrock purchase button. PlayerPoints is only
 * reached by reflection so the plugin builds and runs without it on the classpath.
 */
final class PlayerPointsBridge {

    private PlayerPointsBridge() {
    }

    enum Result {OK, INSUFFICIENT, UNAVAILABLE}

    /** Atomically checks and takes {@code amount} points; {@code OK} only if they were really taken. */
    static Result take(UUID uuid, int amount) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (plugin == null || !plugin.isEnabled()) {
                return Result.UNAVAILABLE;
            }
            Object api = plugin.getClass().getMethod("getAPI").invoke(plugin);
            Method look = api.getClass().getMethod("look", UUID.class);
            int balance = (Integer) look.invoke(api, uuid);
            if (balance < amount) {
                return Result.INSUFFICIENT;
            }
            Method take = api.getClass().getMethod("take", UUID.class, int.class);
            return (Boolean) take.invoke(api, uuid, amount) ? Result.OK : Result.INSUFFICIENT;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Result.UNAVAILABLE;
        }
    }
}
