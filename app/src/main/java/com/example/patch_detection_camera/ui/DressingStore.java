package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class DressingStore {
    private static final String PREF_NAME              = "dressing_records";
    private static final String KEY_RECORDS            = "records_json";
    private static final String KEY_ACTIVE_DRESSING_ID = "active_dressing_id";
    private static final String TAG                    = "DressingStore";

    public static void setActiveDressingId(Context ctx, long id) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_ACTIVE_DRESSING_ID, id).apply();
    }

    public static long getActiveDressingId(Context ctx) {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_ACTIVE_DRESSING_ID, 0L);
    }

    public static void addRecord(Context ctx, DressingRecord record) {
        updateRecord(ctx, record); // upsert
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

    public static void updateRecord(Context ctx, DressingRecord updated) {
        List<DressingRecord> records = loadRecords(ctx);
        boolean found = false;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).getTimestamp() == updated.getTimestamp()) {
                records.set(i, updated);
                found = true;
                break;
            }
        }
        if (!found) records.add(0, updated); // 신규면 맨 앞에 추가
        saveRecords(ctx, records);
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
