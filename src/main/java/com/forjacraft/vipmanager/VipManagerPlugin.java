package com.forjacraft.vipmanager;

import com.forjacraft.vipmanager.command.VipAdminCommand;
import com.forjacraft.vipmanager.command.VipCommand;
import com.forjacraft.vipmanager.config.VipConfig;
import com.forjacraft.vipmanager.integration.ClaimBlockBridge;
import com.forjacraft.vipmanager.integration.LuckPermsBridge;
import com.forjacraft.vipmanager.integration.VipPlaceholderExpansion;
import com.forjacraft.vipmanager.listener.PlayerJoinListener;
import com.forjacraft.vipmanager.service.VipService;
import com.forjacraft.vipmanager.storage.SqliteVipStorage;
import com.forjacraft.vipmanager.storage.VipStorage;
import com.forjacraft.vipmanager.task.VipSweepTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class VipManagerPlugin extends JavaPlugin {

    private VipConfig vipConfig;
    private VipStorage storage;
    private VipService vipService;
    private LuckPermsBridge luckPermsBridge;
    private int sweepTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.vipConfig = new VipConfig(getConfig());

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().severe("LuckPerms not found - VipManager cannot function without it. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.luckPermsBridge = new LuckPermsBridge(vipConfig);

        this.storage = new SqliteVipStorage(new File(getDataFolder(), "vipmanager.db"), getLogger());
        storage.init();

        ClaimBlockBridge claimBlockBridge = new ClaimBlockBridge(this);
        this.vipService = new VipService(vipConfig, storage, luckPermsBridge, claimBlockBridge, getLogger());

        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(this, vipService, luckPermsBridge, vipConfig, getLogger()), this);

        VipCommand vipCommand = new VipCommand(this, vipService, vipConfig);
        var vipPluginCommand = getCommand("vip");
        if (vipPluginCommand != null) {
            vipPluginCommand.setExecutor(vipCommand);
            vipPluginCommand.setTabCompleter(vipCommand);
        }

        VipAdminCommand vipAdminCommand = new VipAdminCommand(this, vipService, vipConfig, luckPermsBridge,
                getLogger(), this::reloadVipConfig);
        var vipAdminPluginCommand = getCommand("vipadmin");
        if (vipAdminPluginCommand != null) {
            vipAdminPluginCommand.setExecutor(vipAdminCommand);
            vipAdminPluginCommand.setTabCompleter(vipAdminCommand);
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VipPlaceholderExpansion(vipService, vipConfig).register();
            getLogger().info("Registered PlaceholderAPI expansion (%vipmanager_*%).");
        }

        long sweepIntervalTicks = vipConfig.sweepIntervalSeconds() * 20L;
        this.sweepTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, new VipSweepTask(vipService, getLogger()), sweepIntervalTicks, sweepIntervalTicks
        ).getTaskId();

        getLogger().info("VipManager enabled - " + vipConfig.tiersLowestFirst().size() + " tiers configured.");
    }

    @Override
    public void onDisable() {
        if (sweepTaskId != -1) {
            Bukkit.getScheduler().cancelTask(sweepTaskId);
        }
        if (storage != null) {
            storage.close();
        }
    }

    private void reloadVipConfig() {
        reloadConfig();
        this.vipConfig.reload(getConfig());
        // NOTE: the sweep task's own period (sweep.interval-seconds) is only read once at
        // startup - changing it in config.yml and reloading does not reschedule the running
        // task. Restart the server if you need a new sweep interval to take effect.
        getLogger().info("Config reloaded.");
    }
}
