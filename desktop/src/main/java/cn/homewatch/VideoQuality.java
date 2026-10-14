package cn.homewatch;

import java.util.Locale;

/** A local preview / recording ceiling. It does not change camera hardware settings. */
public enum VideoQuality {
    AUTO("自动 · 最高可用", 0, 0),
    UHD4K("4K", 3840, 2160),
    QHD1440("1440p", 2560, 1440),
    FHD1080("1080p", 1920, 1080),
    HD720("720p", 1280, 720),
    SD480("480p", 854, 480);

    private final String label;
    private final int maxWidth;
    private final int maxHeight;

    VideoQuality(String label, int maxWidth, int maxHeight) {
        this.label = label;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    public String label() { return label; }
    public int maxWidth() { return maxWidth; }
    public int maxHeight() { return maxHeight; }

    public static VideoQuality parse(String value) {
        if (value == null || value.isBlank()) return AUTO;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (VideoQuality quality : values()) {
            if (quality.name().equals(normalized) || quality.label.equalsIgnoreCase(value.strip())) return quality;
        }
        return switch (normalized) {
            case "4K", "2160P", "2160" -> UHD4K;
            case "1440P", "1440", "2K" -> QHD1440;
            case "1080P", "1080" -> FHD1080;
            case "720P", "720" -> HD720;
            case "480P", "480" -> SD480;
            default -> AUTO;
        };
    }

    /** FFmpeg bounds both dimensions and preserves aspect ratio without upscaling. */
    public String previewFilter() {
        if (this == AUTO) return "";
        return "scale=w='min(iw," + maxWidth + ")':h='min(ih," + maxHeight
                + ")':force_original_aspect_ratio=decrease:force_divisible_by=2";
    }

    public boolean includes(int width, int height) {
        return this == AUTO || (width <= maxWidth && height <= maxHeight);
    }

    @Override public String toString() { return label; }
}
