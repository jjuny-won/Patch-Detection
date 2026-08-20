package com.example.patch_detection_camera.ui;

import java.util.HashMap;
import java.util.Map;

public class WoundMeasurement {
    private long timestamp;
    private float ph;
    private float temperature;
    private float uricAcid;
    private float glucose;

    public WoundMeasurement() {}

    public WoundMeasurement(long timestamp, float ph, float temperature, float uricAcid, float glucose) {
        this.timestamp = timestamp;
        this.ph = ph;
        this.temperature = temperature;
        this.uricAcid = uricAcid;
        this.glucose = glucose;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("timestamp", timestamp);
        map.put("ph", ph);
        map.put("temperature", temperature);
        map.put("uricAcid", uricAcid);
        map.put("glucose", glucose);
        return map;
    }

    public long getTimestamp()   { return timestamp; }
    public float getPh()         { return ph; }
    public float getTemperature(){ return temperature; }
    public float getUricAcid()   { return uricAcid; }
    public float getGlucose()    { return glucose; }
}
