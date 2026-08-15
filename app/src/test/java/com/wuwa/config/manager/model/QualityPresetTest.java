package com.wuwa.config.manager.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class QualityPresetTest {
    @Test
    public void presetIdsAndDefaultAreStable() {
        assertEquals("presets/low", QualityPreset.LOW.getAssetDirectory());
        assertEquals(QualityPreset.EXTREME, QualityPreset.fromId("extreme"));
        assertEquals(QualityPreset.MEDIUM, QualityPreset.fromId(null));
        assertEquals(QualityPreset.MEDIUM, QualityPreset.fromId("unknown"));
    }
}
