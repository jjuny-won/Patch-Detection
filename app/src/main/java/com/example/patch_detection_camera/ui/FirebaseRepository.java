package com.example.patch_detection_camera.ui;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FirebaseRepository {

    private static final String TAG = "FirebaseRepository";
    private static final String COL_DRESSING           = "dressing_records";
    private static final String COL_MEASUREMENTS       = "measurements";
    private static final String COL_DRESSING_SCHEDULES = "dressing_schedules";

    private static FirebaseRepository instance;
    private final FirebaseFirestore db;

    private FirebaseRepository() {
        db = FirebaseFirestore.getInstance();
    }

    public static FirebaseRepository getInstance() {
        if (instance == null) instance = new FirebaseRepository();
        return instance;
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface DressingCallback {
        void onSuccess(List<DressingRecord> records);
        void onFailure(Exception e);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    public interface MeasurementCallback {
        void onSuccess(List<WoundMeasurement> measurements);
        void onFailure(Exception e);
    }

    public interface MeasurementV2Callback {
        void onSuccess(List<Measurement> measurements);
        void onFailure(Exception e);
    }

    // ── Dressing Records ─────────────────────────────────────────────────────

    public void addDressingRecord(DressingRecord record, SimpleCallback callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("dressingId", record.getTimestamp()); // PK: timestamp를 dressingId로 사용
        data.put("timestamp",  record.getTimestamp());
        data.put("dateStr",    record.getDateStr());
        data.put("timeStr",    record.getTimeStr());
        data.put("createdAt",  System.currentTimeMillis());

        db.collection(COL_DRESSING)
                .document(String.valueOf(record.getTimestamp()))
                .set(data)
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "dressing record saved");
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "dressing record save failed", e);
                    if (callback != null) callback.onFailure(e);
                });
    }

    public void loadDressingRecords(DressingCallback callback) {
        db.collection(COL_DRESSING)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(snapshots -> {
                    List<DressingRecord> records = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        long   ts      = doc.getLong("timestamp");
                        String dateStr = doc.getString("dateStr");
                        String timeStr = doc.getString("timeStr");
                        records.add(new DressingRecord(ts, dateStr, timeStr));
                    }
                    callback.onSuccess(records);
                })
                .addOnFailureListener(callback::onFailure);
    }

    public void deleteDressingRecord(long timestamp, SimpleCallback callback) {
        db.collection(COL_DRESSING)
                .document(String.valueOf(timestamp))
                .delete()
                .addOnSuccessListener(unused -> { if (callback != null) callback.onSuccess(); })
                .addOnFailureListener(e -> { if (callback != null) callback.onFailure(e); });
    }


    // ── Measurements (new schema) ─────────────────────────────────────────────

    public void addMeasurement(Measurement m, SimpleCallback callback) {
        db.collection(COL_MEASUREMENTS)
                .document(String.valueOf(m.capturedAt))
                .set(m.toMap())
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "measurement saved");
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "measurement save failed", e);
                    if (callback != null) callback.onFailure(e);
                });
    }

    public void loadMeasurements(MeasurementV2Callback callback) {
        db.collection(COL_MEASUREMENTS)
                .orderBy("capturedAt", Query.Direction.ASCENDING)
                .get()
                .addOnSuccessListener(snapshots -> {
                    List<Measurement> list = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        list.add(Measurement.fromMap(doc.getData()));
                    }
                    callback.onSuccess(list);
                })
                .addOnFailureListener(callback::onFailure);
    }

    // ── Dressing Schedules ────────────────────────────────────────────────────

    public void saveDressingSchedule(DressingSchedule schedule, SimpleCallback callback) {
        db.collection(COL_DRESSING_SCHEDULES)
                .document("active")
                .set(schedule.toMap())
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "dressing schedule saved");
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "dressing schedule save failed", e);
                    if (callback != null) callback.onFailure(e);
                });
    }
}
