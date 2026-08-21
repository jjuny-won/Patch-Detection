package com.example.patch_detection_camera.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Measurement {
    public long capturedAt;
    public long createdAt;
    public boolean calibrationApplied;
    public long dressingId = 0;
    public String imageUrl = "";
    public String calibrationStatus = "";  // PASS / WARN / FAIL / NONE
    public double medianDeltaE = 0;
    public double maxDeltaE = 0;
    public List<SensorValue> sensors = new ArrayList<>();
    public List<ColorMarkerValue> colorMarkers = new ArrayList<>();

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("capturedAt",          capturedAt);
        m.put("createdAt",           createdAt > 0 ? createdAt : System.currentTimeMillis());
        m.put("calibrationApplied",  calibrationApplied);
        m.put("dressingId",          dressingId);
        m.put("imageUrl",            imageUrl != null ? imageUrl : "");
        m.put("calibrationStatus",   calibrationStatus != null ? calibrationStatus : "NONE");
        m.put("medianDeltaE",        medianDeltaE);
        m.put("maxDeltaE",           maxDeltaE);

        List<Map<String, Object>> sl = new ArrayList<>();
        for (SensorValue s : sensors) sl.add(s.toMap());
        m.put("sensors", sl);

        List<Map<String, Object>> cl = new ArrayList<>();
        for (ColorMarkerValue c : colorMarkers) cl.add(c.toMap());
        m.put("colorMarkers", cl);

        return m;
    }

    @SuppressWarnings("unchecked")
    public static Measurement fromMap(Map<String, Object> m) {
        Measurement ms = new Measurement();
        Object ca = m.get("capturedAt");
        if (ca instanceof Long) ms.capturedAt = (Long) ca;
        Object cr = m.get("createdAt");
        if (cr instanceof Long) ms.createdAt = (Long) cr;
        Object cb = m.get("calibrationApplied");
        if (cb instanceof Boolean) ms.calibrationApplied = (Boolean) cb;
        Object di = m.get("dressingId");
        if (di instanceof Long) ms.dressingId = (Long) di;
        Object iu = m.get("imageUrl");
        if (iu != null) ms.imageUrl = iu.toString();
        Object cs = m.get("calibrationStatus");
        if (cs != null) ms.calibrationStatus = cs.toString();
        Object mde = m.get("medianDeltaE");
        if (mde instanceof Double) ms.medianDeltaE = (Double) mde;
        Object xde = m.get("maxDeltaE");
        if (xde instanceof Double) ms.maxDeltaE = (Double) xde;

        Object sRaw = m.get("sensors");
        if (sRaw instanceof List) {
            for (Object o : (List<?>) sRaw) {
                SensorValue sv = SensorValue.fromMap(o);
                if (sv != null) ms.sensors.add(sv);
            }
        }
        Object cRaw = m.get("colorMarkers");
        if (cRaw instanceof List) {
            for (Object o : (List<?>) cRaw) {
                ColorMarkerValue cv = ColorMarkerValue.fromMap(o);
                if (cv != null) ms.colorMarkers.add(cv);
            }
        }
        return ms;
    }

    public SensorValue getSensor(String type) {
        for (SensorValue s : sensors) {
            if (type.equalsIgnoreCase(s.sensorType)) return s;
        }
        return null;
    }
}
