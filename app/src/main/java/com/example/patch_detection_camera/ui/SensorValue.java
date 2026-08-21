package com.example.patch_detection_camera.ui;

import java.util.HashMap;
import java.util.Map;

public class SensorValue {
    public String sensorType;
    public int rawR, rawG, rawB;
    public int correctedR, correctedG, correctedB;
    public String predictedValue; // ex: "pH_6.1", "33.8C", "52%"
    public float numericValue;
    public String unit;
    public float deltaE;
    public String statusCode;
    public String statusLabel;
    public long createdAt;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("sensorType",     sensorType != null ? sensorType : "");
        m.put("rawR", rawR); m.put("rawG", rawG); m.put("rawB", rawB);
        m.put("correctedR", correctedR); m.put("correctedG", correctedG); m.put("correctedB", correctedB);
        m.put("predictedValue", predictedValue != null ? predictedValue : "");
        m.put("numericValue",   (double) numericValue);
        m.put("unit",           unit != null ? unit : "");
        m.put("deltaE",         (double) deltaE);
        m.put("statusCode",     statusCode != null ? statusCode : "");
        m.put("statusLabel",    statusLabel != null ? statusLabel : "");
        m.put("createdAt",      createdAt > 0 ? createdAt : System.currentTimeMillis());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SensorValue fromMap(Object obj) {
        if (!(obj instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) obj;
        SensorValue s = new SensorValue();
        s.sensorType     = str(m, "sensorType");
        s.rawR           = toInt(m.get("rawR")); s.rawG = toInt(m.get("rawG")); s.rawB = toInt(m.get("rawB"));
        s.correctedR     = toInt(m.get("correctedR")); s.correctedG = toInt(m.get("correctedG")); s.correctedB = toInt(m.get("correctedB"));
        s.predictedValue = str(m, "predictedValue");
        s.numericValue   = toFloat(m.get("numericValue"));
        s.unit           = str(m, "unit");
        s.deltaE         = toFloat(m.get("deltaE"));
        s.statusCode     = str(m, "statusCode");
        s.statusLabel    = str(m, "statusLabel");
        s.createdAt      = toLong(m.get("createdAt"));
        return s;
    }

    private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v != null ? v.toString() : ""; }
    private static int toInt(Object v) {
        if (v instanceof Long)   return ((Long) v).intValue();
        if (v instanceof Double) return ((Double) v).intValue();
        return 0;
    }
    private static float toFloat(Object v) {
        if (v instanceof Double) return ((Double) v).floatValue();
        if (v instanceof Long)   return ((Long) v).floatValue();
        return 0f;
    }
    private static long toLong(Object v) {
        if (v instanceof Long) return (Long) v;
        return 0L;
    }
}
