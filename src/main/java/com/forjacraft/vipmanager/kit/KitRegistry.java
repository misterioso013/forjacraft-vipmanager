package com.forjacraft.vipmanager.kit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads and holds all 12 VIP kit definitions (4 tiers x 3 periods) from
 * {@code plugins/VipManager/kits/<tier>_<period>.yml}. Bundled defaults (in
 * {@code src/main/resources/kits/}) are copied out on first run, exactly
 * mirroring the content of the PlayerKits2 kits they replace - editable
 * afterwards without recompiling.
 */
public final class KitRegistry {

    public static final List<String> TIERS = List.of("barao", "marques", "duque", "rei");
    public static final List<String> PERIODS = List.of("diario", "semanal", "mensal");

    private final Plugin plugin;
    private final Logger logger;
    private final File kitsFolder;
    private Map<String, KitDefinition> kits = Collections.emptyMap();

    public KitRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.kitsFolder = new File(plugin.getDataFolder(), "kits");
    }

    public void load() {
        if (!kitsFolder.exists() && !kitsFolder.mkdirs()) {
            logger.warning("Could not create kits folder: " + kitsFolder);
        }

        Map<String, KitDefinition> loaded = new LinkedHashMap<>();
        for (String tier : TIERS) {
            for (String period : PERIODS) {
                String fileName = tier + "_" + period + ".yml";
                File file = new File(kitsFolder, fileName);
                copyDefaultIfMissing(fileName, file);

                if (!file.exists()) {
                    logger.warning("Kit file missing and no bundled default found: " + fileName + " - skipping");
                    continue;
                }
                KitDefinition definition = loadKitFile(tier, period, file);
                loaded.put(definition.kitId(), definition);
            }
        }
        this.kits = Collections.unmodifiableMap(loaded);
        logger.info("Loaded " + kits.size() + " VIP kits.");
    }

    private void copyDefaultIfMissing(String fileName, File target) {
        if (target.exists()) {
            return;
        }
        String resourcePath = "kits/" + fileName;
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return;
            }
            Files.copy(in, target.toPath());
        } catch (Exception e) {
            logger.warning("Failed to copy bundled default kit '" + resourcePath + "': " + e.getMessage());
        }
    }

    private KitDefinition loadKitFile(String tier, String period, File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        long cooldownSeconds = yaml.getLong("cooldown-seconds", 0L);

        List<KitItemSpec> items = new ArrayList<>();
        List<Map<?, ?>> itemMaps = yaml.getMapList("items");
        for (Map<?, ?> raw : itemMaps) {
            KitItemSpec spec = KitItemSpecParser.parse(raw, tier + "_" + period + ".yml", logger);
            if (spec != null) {
                items.add(spec);
            }
        }
        return new KitDefinition(tier, period, cooldownSeconds, List.copyOf(items));
    }

    public java.util.Optional<KitDefinition> get(String tier, String period) {
        return java.util.Optional.ofNullable(kits.get(tier + "_" + period));
    }

    public boolean isKnownTier(String tier) {
        return TIERS.contains(tier);
    }

    public boolean isKnownPeriod(String period) {
        return PERIODS.contains(period);
    }

    public int tierRank(String tier) {
        return TIERS.indexOf(tier);
    }
}
