package com.example.patch_detection_camera.ui;

import java.util.HashMap;
import java.util.Map;

public class ColorMarkerValue {
    public String colorType;
    public int rawR, rawG, rawB;
    public int correctedR, correctedG, correctedB;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("colorType", colorType != null ? colorType : "");
        m.put("rawR", rawR); m.put("rawG", rawG); m.put("rawB", rawB);
        m.put("correctedR", correctedR); m.put("correctedG", correctedG); m.put("correctedB", correctedB);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ColorMarkerValue fromMap(Object obj) {
        if (!(obj instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) obj;
        ColorMarkerValue c = new ColorMarkerValue();
        c.colorType  = str(m, "colorType");
        c.rawR       = toInt(m.get("rawR")); c.rawG = toInt(m.get("rawG")); c.rawB = toInt(m.get("rawB"));
        c.correctedR = toInt(m.get("correctedR")); c.correctedG = toInt(m.get("correctedG")); c.correctedB = toInt(m.get("correctedB"));
        return c;
    }

    private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v != null ? v.toString() : ""; }
    private static int toInt(Object v) {
        if (v instanceof Long) return ((Long) v).intValue();
        if (v instanceof Double) return ((Double) v).intValue();
        return 0;
    }
}
