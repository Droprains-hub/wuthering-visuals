package com.wuwa.config.manager.model;

public enum QualityPreset {
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    EXTREME("extreme", "极致");

    private static final String ASSET_ROOT = "presets";

    private final String id;
    private final String displayName;

    QualityPreset(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAssetDirectory() {
        return ASSET_ROOT + "/" + id;
    }

    public static QualityPreset fromId(String id) {
        if (id != null) {
            for (QualityPreset preset : values()) {
                if (preset.id.equals(id)) return preset;
            }
        }
        return MEDIUM;
    }
}
