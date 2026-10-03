package com.forjacraft.vipmanager;

import com.forjacraft.vipmanager.bedrock.BedrockMenuRegistry;
import com.forjacraft.vipmanager.bedrock.BedrockMenuService;
import com.forjacraft.vipmanager.command.BedrockMenuCommand;
import com.forjacraft.vipmanager.command.KitCommand;
import com.forjacraft.vipmanager.command.VipAdminCommand;
import com.forjacraft.vipmanager.command.VipCommand;
import com.forjacraft.vipmanager.config.VipConfig;
import com.forjacraft.vipmanager.integration.ClaimBlockBridge;
import com.forjacraft.vipmanager.integration.LuckPermsBridge;
import com.forjacraft.vipmanager.integration.VipPlaceholderExpansion;
import com.forjacraft.vipmanager.kit.FreeKitRegistry;
import com.forjacraft.vipmanager.kit.FreeKitService;
import com.forjacraft.vipmanager.kit.KitCooldownSync;
import com.forjacraft.vipmanager.kit.KitItemResolver;
import com.forjacraft.vipmanager.kit.KitPreviewListener;
import com.forjacraft.vipmanager.kit.KitRegistry;
import com.forjacraft.vipmanager.kit.KitService;
import com.forjacraft.vipmanager.listener.PlayerJoinListener;
import com.forjacraft.vipmanager.service.VipService;
import com.forjacraft.vipmanager.storage.MysqlVipStorage;
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
    private BedrockMenuRegistry bedrockMenuRegistry;
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

        this.storage = createStorage();
        storage.init();

        ClaimBlockBridge claimBlockBridge = new ClaimBlockBridge(this);
        this.vipService = new VipService(vipConfig, storage, luckPermsBridge, claimBlockBridge, getLogger());

        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(this, vipService, luckPermsBridge, vipConfig, getLogger()), this);

        if (Bukkit.getPluginManager().getPlugin("Nexo") == null) {
            getLogger().warning("Nexo not found - VIP kit commands will still register, but any Nexo item in a "
                    + "kit will fail to resolve until Nexo is installed.");
        }
        KitRegistry kitRegistry = new KitRegistry(this);
        kitRegistry.load();
        KitItemResolver kitItemResolver = new KitItemResolver(getLogger());
        KitService kitService = new KitService(kitRegistry, kitItemResolver, vipService, storage, getLogger());
        vipService.setTierActivationListener(new KitCooldownSync(storage, getLogger()));
        getServer().getPluginManager().registerEvents(new KitPreviewListener(this), this);

        VipCommand vipCommand = new VipCommand(this, vipService, vipConfig, kitService);
        var vipPluginCommand = getCommand("vip");
        if (vipPluginCommand != null) {
            vipPluginCommand.setExecutor(vipCommand);
            vipPluginCommand.setTabCompleter(vipCommand);
        }

        FreeKitRegistry freeKitRegistry = new FreeKitRegistry(this);
        freeKitRegistry.load();
        FreeKitService freeKitService = new FreeKitService(freeKitRegistry, kitItemResolver, storage, getLogger());
        KitCommand kitCommand = new KitCommand(this, freeKitService, vipConfig);
        var kitPluginCommand = getCommand("kit");
        if (kitPluginCommand != null) {
            kitPluginCommand.setExecutor(kitCommand);
            kitPluginCommand.setTabCompleter(kitCommand);
        }

        VipAdminCommand vipAdminCommand = new VipAdminCommand(this, vipService, vipConfig, luckPermsBridge,
                getLogger(), this::reloadVipConfig);
        var vipAdminPluginCommand = getCommand("vipadmin");
        if (vipAdminPluginCommand != null) {
            vipAdminPluginCommand.setExecutor(vipAdminCommand);
            vipAdminPluginCommand.setTabCompleter(vipAdminCommand);
        }

        this.bedrockMenuRegistry = new BedrockMenuRegistry(this);
        bedrockMenuRegistry.load();
        BedrockMenuService bedrockMenuService = new BedrockMenuService(this, bedrockMenuRegistry);
        if (!bedrockMenuService.isFloodgateAvailable()) {
            getLogger().info("Floodgate not found - Bedrock menus are disabled (Bedrock players will get the Java chest menus).");
        }
        BedrockMenuCommand bedrockMenuCommand = new BedrockMenuCommand(bedrockMenuService, bedrockMenuRegistry);
        var bedrockPluginCommand = getCommand("bedrockmenu");
        if (bedrockPluginCommand != null) {
            bedrockPluginCommand.setExecutor(bedrockMenuCommand);
            bedrockPluginCommand.setTabCompleter(bedrockMenuCommand);
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VipPlaceholderExpansion(vipService, vipConfig, bedrockMenuService).register();
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

    private VipStorage createStorage() {
        String type = getConfig().getString("storage.type", "sqlite");
        if ("mysql".equalsIgnoreCase(type)) {
            return new MysqlVipStorage(
                    getConfig().getString("storage.mysql.host", "localhost"),
                    getConfig().getInt("storage.mysql.port", 3306),
                    getConfig().getString("storage.mysql.database", "vipmanager"),
                    getConfig().getString("storage.mysql.username", "vipmanager"),
                    getConfig().getString("storage.mysql.password", ""),
                    getLogger());
        }
        return new SqliteVipStorage(new File(getDataFolder(), "vipmanager.db"), getLogger());
    }

    private void reloadVipConfig() {
        reloadConfig();
        this.vipConfig.reload(getConfig());
        if (bedrockMenuRegistry != null) {
            bedrockMenuRegistry.load();
        }
        // NOTE: the sweep task's own period (sweep.interval-seconds) is only read once at
        // startup - changing it in config.yml and reloading does not reschedule the running
        // task. Restart the server if you need a new sweep interval to take effect.
        getLogger().info("Config reloaded.");
    }
}
