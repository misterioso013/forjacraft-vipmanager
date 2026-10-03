package com.forjacraft.vipmanager.command;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses combinable duration strings like {@code 30d}, {@code 12h}, {@code 1d12h}
 * into a number of seconds. No existing dependency in this project covers this,
 * so it's a small self-contained parser rather than pulling in a library.
 */
public final class DurationParser {

    private static final Pattern TOKEN = Pattern.compile("(?i)(\\d+)([smhdw])");

    private DurationParser() {
    }

    /**
     * @throws IllegalArgumentException if the string is empty, contains no
     *         valid tokens, or contains anything that isn't a recognized token.
     */
    public static long parseSeconds(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Duration string is empty");
        }
        Matcher matcher = TOKEN.matcher(input);
        long totalSeconds = 0;
        int matchedChars = 0;
        boolean matchedAny = false;

        while (matcher.find()) {
            matchedAny = true;
            matchedChars += matcher.group().length();
            long amount = Long.parseLong(matcher.group(1));
            char unit = Character.toLowerCase(matcher.group(2).charAt(0));
            totalSeconds += amount * secondsPerUnit(unit);
        }

        if (!matchedAny || matchedChars != input.trim().length()) {
            throw new IllegalArgumentException(
                    "Invalid duration '" + input + "'. Use combinations like 30d, 12h, 1d12h30m.");
        }
        return totalSeconds;
    }

    private static long secondsPerUnit(char unit) {
        return switch (unit) {
            case 's' -> 1L;
            case 'm' -> 60L;
            case 'h' -> 3600L;
            case 'd' -> 86_400L;
            case 'w' -> 604_800L;
            default -> throw new IllegalArgumentException("Unknown duration unit '" + unit + "'");
        };
    }

    /** Formats seconds back into a short human-readable string, e.g. "5d 3h". */
    public static String format(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0s";
        }
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    /**
     * Formats seconds as days/hours only (rounding the rest up into the hour), e.g.
     * "29d 14h" or "3h". Used for long cooldown messages (kit cooldowns run up to 30
     * days) where minute/second precision is just noise.
     */
    public static String formatDaysHours(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0h";
        }
        long roundedUpHours = (totalSeconds + 3599L) / 3600L;
        long days = roundedUpHours / 24L;
        long hours = roundedUpHours % 24L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        sb.append(hours).append("h");
        return sb.toString().trim();
    }
}
