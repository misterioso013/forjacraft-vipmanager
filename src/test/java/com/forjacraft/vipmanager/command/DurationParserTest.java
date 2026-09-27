package com.forjacraft.vipmanager.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationParserTest {

    @Test
    void parsesSingleUnit() {
        assertEquals(30L * 86_400L, DurationParser.parseSeconds("30d"));
        assertEquals(12L * 3600L, DurationParser.parseSeconds("12h"));
        assertEquals(45L * 60L, DurationParser.parseSeconds("45m"));
        assertEquals(90L, DurationParser.parseSeconds("90s"));
        assertEquals(2L * 604_800L, DurationParser.parseSeconds("2w"));
    }

    @Test
    void parsesCombinedUnits() {
        long expected = 1L * 86_400L + 12L * 3600L + 30L * 60L;
        assertEquals(expected, DurationParser.parseSeconds("1d12h30m"));
    }

    @Test
    void isCaseInsensitive() {
        assertEquals(86_400L, DurationParser.parseSeconds("1D"));
    }

    @Test
    void rejectsEmptyString() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds(""));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds(null));
    }

    @Test
    void rejectsGarbageInput() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("banana"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("30x"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("30d garbage"));
    }

    @Test
    void formatsSecondsHumanReadable() {
        assertEquals("0s", DurationParser.format(0));
        assertEquals("30s", DurationParser.format(30));
        assertEquals("5m 0s", DurationParser.format(300));
        assertEquals("1d 0h 0m 0s", DurationParser.format(86_400));
    }
}
