package com.forjacraft.vipmanager.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockTextTest {

    @Test
    void translatesLegacyCodes() {
        assertEquals("§6§lLOJA §7texto", BedrockText.toBedrock("&6&lLOJA &7texto"));
    }

    @Test
    void snapsHexToNearestLegacyColor() {
        assertEquals("§6§lKIT", BedrockText.toBedrock("&x&F&F&D&9&0&0&lKIT"));
        assertEquals("§3", BedrockText.toBedrock("&#08A2FB"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", BedrockText.toBedrock(null));
    }
}
