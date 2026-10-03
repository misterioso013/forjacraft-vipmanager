package com.forjacraft.vipmanager.kit;

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
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Loads the free (non-VIP) kits - novato/claim/fazendeiro/comida - from
 * {@code plugins/VipManager/freekits/<id>.yml}. Bundled defaults (in
 * {@code src/main/resources/freekits/}) are copied out on first run, migrated
 * 1:1 from the PlayerKits2 kit files they replace. Deliberately separate from
 * {@link KitRegistry}: these kits have no tier/rank concept at all.
 */
public final class FreeKitRegistry {

    public static final List<String> IDS = List.of("novato", "claim", "fazendeiro", "comida");

    private final Plugin plugin;
    private final Logger logger;
    private final File kitsFolder;
    private Map<String, FreeKitDefinition> kits = Collections.emptyMap();

    public FreeKitRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.kitsFolder = new File(plugin.getDataFolder(), "freekits");
    }

    public void load() {
        if (!kitsFolder.exists() && !kitsFolder.mkdirs()) {
            logger.warning("Could not create freekits folder: " + kitsFolder);
        }

        Map<String, FreeKitDefinition> loaded = new LinkedHashMap<>();
        for (String id : IDS) {
            String fileName = id + ".yml";
            File file = new File(kitsFolder, fileName);
            copyDefaultIfMissing(fileName, file);

            if (!file.exists()) {
                logger.warning("Free kit file missing and no bundled default found: " + fileName + " - skipping");
                continue;
            }
            FreeKitDefinition definition = loadKitFile(id, file);
            loaded.put(id, definition);
        }
        this.kits = Collections.unmodifiableMap(loaded);
        logger.info("Loaded " + kits.size() + " free kits.");
    }

    private void copyDefaultIfMissing(String fileName, File target) {
        if (target.exists()) {
            return;
        }
        String resourcePath = "freekits/" + fileName;
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return;
            }
            Files.copy(in, target.toPath());
        } catch (Exception e) {
            logger.warning("Failed to copy bundled default free kit '" + resourcePath + "': " + e.getMessage());
        }
    }

    private FreeKitDefinition loadKitFile(String id, File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean oneTime = yaml.getBoolean("one-time", false);
        long cooldownSeconds = yaml.getLong("cooldown-seconds", 0L);

        List<KitItemSpec> items = new ArrayList<>();
        List<Map<?, ?>> itemMaps = yaml.getMapList("items");
        for (Map<?, ?> raw : itemMaps) {
            KitItemSpec spec = KitItemSpecParser.parse(raw, id + ".yml", logger);
            if (spec != null) {
                items.add(spec);
            }
        }
        return new FreeKitDefinition(id, oneTime, cooldownSeconds, List.copyOf(items));
    }

    public Optional<FreeKitDefinition> get(String id) {
        return Optional.ofNullable(kits.get(id));
    }

    public boolean isKnownKit(String id) {
        return IDS.contains(id);
    }
}
