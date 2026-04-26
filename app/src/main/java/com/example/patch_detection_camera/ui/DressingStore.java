package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class DressingStore {
    private static final String PREF_NAME   = "dressing_records";
    private static final String KEY_RECORDS = "records_json";
    private static final String TAG         = "DressingStore";

    public static void addRecord(Context ctx, DressingRecord record) {
        List<DressingRecord> records = loadRecords(ctx);
        records.add(0, record); // newest first
        saveRecords(ctx, records);
    }

    public static List<DressingRecord> loadRecords(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_RECORDS, "[]");
        List<DressingRecord> records = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                records.add(DressingRecord.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e(TAG, "loadRecords error", e);
        }
        return records;
    }

    public static void deleteRecord(Context ctx, long timestamp) {
        List<DressingRecord> records = loadRecords(ctx);
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).getTimestamp() == timestamp) {
                records.remove(i);
                break;
            }
        }
        saveRecords(ctx, records);
    }

    private static void saveRecords(Context ctx, List<DressingRecord> records) {
        JSONArray arr = new JSONArray();
        for (DressingRecord r : records) {
            try { arr.put(r.toJson()); } catch (JSONException e) { Log.e(TAG, "save error", e); }
        }
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECORDS, arr.toString()).apply();
    }
}
