package cn.homewatch;

import java.io.IOException;

/** Storage policy shown in the NVR settings, independent of a selected camera. */
public interface NvrStorage {
    RecorderSettings settings();
    long usedBytes();
    boolean isRequested();
    boolean isRecording();
    void applySettings(RecorderSettings settings) throws IOException;
    void cleanupNow();
}
