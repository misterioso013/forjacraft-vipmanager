package com.forjacraft.vipmanager.bedrock;

import java.util.List;

/**
 * One native Bedrock form (a "simple form": a title, optional body text and a
 * vertical list of buttons). Loaded from {@code plugins/VipManager/bedrock-menus/<id>.yml}.
 */
public record BedrockMenu(String id, String title, String content, List<Button> buttons) {

    /** {@code imageUrl} may be null; {@code commands} run as the player, in order, on the main thread. */
    public record Button(String text, String imageUrl, List<String> commands) {
    }
}
