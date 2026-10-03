package com.forjacraft.vipmanager.bedrock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Java-style chat formatting ({@code &a}, {@code &#rrggbb}, {@code &x&r&r&g&g&b&b})
 * into what a Bedrock form can render: only the 16 legacy colors with {@code §}.
 * Hex colors are snapped to the nearest legacy color.
 */
final class BedrockText {

    private static final Pattern HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern X_HEX = Pattern.compile(
            "[&§][xX]([&§][0-9a-fA-F]){6}");
    private static final Pattern CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private static final char[] LEGACY = "0123456789abcdef".toCharArray();
    private static final int[] RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private BedrockText() {
    }

    static String toBedrock(String text) {
        if (text == null) {
            return "";
        }
        String out = X_HEX.matcher(text).replaceAll(m -> {
            String digits = m.group().replaceAll("[&§xX]", "");
            return Matcher.quoteReplacement("§" + nearest(Integer.parseInt(digits, 16)));
        });
        out = HEX.matcher(out).replaceAll(m ->
                Matcher.quoteReplacement("§" + nearest(Integer.parseInt(m.group(1), 16))));
        return CODE.matcher(out).replaceAll("§$1");
    }

    private static char nearest(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < RGB.length; i++) {
            int dr = r - (RGB[i] >> 16 & 0xFF), dg = g - (RGB[i] >> 8 & 0xFF), db = b - (RGB[i] & 0xFF);
            long d = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return LEGACY[best];
    }
}
