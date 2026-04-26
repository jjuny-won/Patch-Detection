package com.example.patch_detection_camera.ui;

import org.json.JSONException;
import org.json.JSONObject;

public class DressingRecord {
    private final long timestamp;
    private final String dateStr;
    private final String timeStr;

    public DressingRecord(long timestamp, String dateStr, String timeStr) {
        this.timestamp = timestamp;
        this.dateStr = dateStr;
        this.timeStr = timeStr;
    }

    public long getTimestamp() { return timestamp; }
    public String getDateStr()  { return dateStr; }
    public String getTimeStr()  { return timeStr; }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("timestamp", timestamp);
        o.put("dateStr",   dateStr);
        o.put("timeStr",   timeStr);
        return o;
    }

    public static DressingRecord fromJson(JSONObject o) throws JSONException {
        return new DressingRecord(
                o.getLong("timestamp"),
                o.getString("dateStr"),
                o.getString("timeStr")
        );
    }
}
