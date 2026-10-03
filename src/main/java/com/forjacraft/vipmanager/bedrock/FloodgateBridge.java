package com.forjacraft.vipmanager.bedrock;

import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * The only class that touches the Floodgate/Cumulus API. It is loaded lazily, so
 * the plugin still starts on a server without Floodgate as long as nothing calls
 * into here ({@link BedrockMenuService} guards every call behind a plugin check).
 */
final class FloodgateBridge {

    private FloodgateBridge() {
    }

    static boolean isBedrock(UUID uuid) {
        return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
    }

    static void send(UUID uuid, String title, String content, java.util.List<BedrockMenu.Button> buttons,
                     IntConsumer onClick) {
        SimpleForm.Builder form = SimpleForm.builder().title(title);
        if (content != null && !content.isEmpty()) {
            form.content(content);
        }
        for (BedrockMenu.Button button : buttons) {
            if (button.imageUrl() != null && !button.imageUrl().isBlank()) {
                form.button(BedrockText.toBedrock(button.text()), FormImage.Type.URL, button.imageUrl());
            } else {
                form.button(BedrockText.toBedrock(button.text()));
            }
        }
        form.validResultHandler(response -> onClick.accept(response.clickedButtonId()));
        FloodgateApi.getInstance().sendForm(uuid, form);
    }
}
