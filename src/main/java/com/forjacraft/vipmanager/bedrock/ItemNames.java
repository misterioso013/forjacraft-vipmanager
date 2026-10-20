package com.forjacraft.vipmanager.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Portuguese item names for the Bedrock shop. Reads Geyser's own {@code locales/pt_br.json}
 * (the Java client language file it downloads), keyed by the Paper translation key, and falls
 * back to a humanized material name when the file or the key is missing.
 */
final class ItemNames {

    private final Map<String, String> table;

    ItemNames(File geyserLocale, Logger logger) {
        Map<String, String> loaded = Map.of();
        if (geyserLocale != null && geyserLocale.isFile()) {
            try (Reader reader = Files.newBufferedReader(geyserLocale.toPath(), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                java.util.HashMap<String, String> map = new java.util.HashMap<>();
                for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                    String key = e.getKey();
                    if ((key.startsWith("item.minecraft.") || key.startsWith("block.minecraft.")) && e.getValue().isJsonPrimitive()) {
                        map.put(key, e.getValue().getAsString());
                    }
                }
                loaded = map;
            } catch (IOException | RuntimeException e) {
                logger.warning("Could not read " + geyserLocale + " for shop item names: " + e.getMessage());
            }
        } else {
            logger.info("Geyser pt_br locale not found; Bedrock shop will use English-style item names.");
        }
        this.table = loaded;
    }

    String name(ItemStack stack) {
        if (stack == null) {
            return "?";
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(meta.displayName());
            if (!plain.isBlank()) {
                return plain;
            }
        }
        String translated = table.get(stack.getType().translationKey());
        return translated != null ? translated : humanize(stack.getType().name());
    }

    static String humanize(String enumName) {
        StringBuilder out = new StringBuilder();
        for (String part : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
