package com.forjacraft.vipmanager.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemNamesTest {

    @Test
    void humanizesMaterialNames() {
        assertEquals("Oak Log", ItemNames.humanize("OAK_LOG"));
        assertEquals("Dirt", ItemNames.humanize("DIRT"));
        assertEquals("Netherite Upgrade Smithing Template", ItemNames.humanize("NETHERITE_UPGRADE_SMITHING_TEMPLATE"));
    }
}
