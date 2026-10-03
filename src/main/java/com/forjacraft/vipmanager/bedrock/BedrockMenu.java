package com.forjacraft.vipmanager.bedrock;

import java.util.List;

/**
 * One native Bedrock form (a "simple form": a title, optional body text and a
 * vertical list of buttons). Loaded from {@code plugins/VipManager/bedrock-menus/<id>.yml}.
 */
public record BedrockMenu(String id, String title, String content, List<Button> buttons) {

    /**
     * {@code imageUrl} may be null; {@code commands} run as the player, in order, on the main thread.
     * <p>
     * A purchase button also has {@code price} (Cash, i.e. PlayerPoints; 0 = not a purchase) and
     * {@code grantCommands}, run from the console after the price was really taken, with
     * {@code %player%} replaced. {@code unlessPermission} refuses the purchase when the player already
     * has that node, and {@code successMessage} is sent after the grant.
     */
    public record Button(String text, String imageUrl, List<String> commands, int price,
                         List<String> grantCommands, String unlessPermission, String successMessage) {

        public Button(String text, String imageUrl, List<String> commands) {
            this(text, imageUrl, commands, 0, List.of(), null, null);
        }

        public boolean isPurchase() {
            return price > 0 || !grantCommands.isEmpty();
        }
    }
}
