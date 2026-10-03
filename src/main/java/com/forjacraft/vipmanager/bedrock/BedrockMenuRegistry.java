package com.forjacraft.vipmanager.bedrock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Loads every {@code *.yml} in {@code plugins/VipManager/bedrock-menus/}. There are
 * no bundled defaults on purpose: the files are generated from the server's
 * DeluxeMenus menus (see tools/gen_bedrock_forms.py in the server repo) and live
 * with the server config.
 */
public final class BedrockMenuRegistry {

    private final Logger logger;
    private final File folder;
    private volatile Map<String, BedrockMenu> menus = Collections.emptyMap();

    public BedrockMenuRegistry(Plugin plugin) {
        this.logger = plugin.getLogger();
        this.folder = new File(plugin.getDataFolder(), "bedrock-menus");
    }

    public void load() {
        if (!folder.exists() && !folder.mkdirs()) {
            logger.warning("Could not create bedrock-menus folder: " + folder);
        }
        Map<String, BedrockMenu> loaded = new LinkedHashMap<>();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - ".yml".length());
                try {
                    loaded.put(id, parse(id, YamlConfiguration.loadConfiguration(file)));
                } catch (Exception e) {
                    logger.warning("Skipping invalid Bedrock menu '" + file.getName() + "': " + e.getMessage());
                }
            }
        }
        this.menus = Collections.unmodifiableMap(loaded);
        logger.info("Loaded " + menus.size() + " Bedrock menus.");
    }

    private BedrockMenu parse(String id, YamlConfiguration yaml) {
        String title = yaml.getString("title", id);
        String content = yaml.getString("content", "");
        List<BedrockMenu.Button> buttons = new ArrayList<>();
        for (Map<?, ?> raw : yaml.getMapList("buttons")) {
            Object text = raw.get("text");
            if (text == null) {
                throw new IllegalArgumentException("a button has no 'text'");
            }
            Object image = raw.get("image");
            List<String> commands = new ArrayList<>();
            Object rawCommands = raw.get("commands");
            if (rawCommands instanceof List<?> list) {
                for (Object command : list) {
                    commands.add(String.valueOf(command));
                }
            }
            buttons.add(new BedrockMenu.Button(String.valueOf(text),
                    image == null ? null : String.valueOf(image), List.copyOf(commands)));
        }
        if (buttons.isEmpty()) {
            throw new IllegalArgumentException("no buttons");
        }
        return new BedrockMenu(id, title, content, List.copyOf(buttons));
    }

    public Optional<BedrockMenu> get(String id) {
        return Optional.ofNullable(menus.get(id));
    }

    public java.util.Set<String> ids() {
        return menus.keySet();
    }
}
