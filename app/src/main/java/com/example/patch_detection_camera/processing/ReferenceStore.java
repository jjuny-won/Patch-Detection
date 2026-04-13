package com.example.patch_detection_camera.processing;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class ReferenceStore {

    private static final String TAG = "ReferenceStore";

    private static final String PREFS = "reference_store";
    private static final String KEY_USING_TEST_DATA = "using_test_data";

    private static final String ASSET_REFERENCE_PATH = "debug/reference.json";

    private static final String FILE_SAVED_REFERENCE = "saved_reference.json";
    private static final String FILE_RUNS_REFERENCE = "runs_reference.json";
    private static final String FILE_RUNS_SAVED = "runs_saved.json";

    private ReferenceStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void setUsingTestData(Context context, boolean using) {
        prefs(context).edit().putBoolean(KEY_USING_TEST_DATA, using).apply();
    }

    public static boolean isUsingTestData(Context context) {
        return prefs(context).getBoolean(KEY_USING_TEST_DATA, false);
    }

    public static String getActiveReferenceSource(Context context) {
        if (isUsingTestData(context)) return "REFERENCE";
        if (hasSavedReference(context)) return "SAVED";
        return "REFERENCE";
    }

    public static float[] loadActiveReferenceRgb(Context context) {
        if (isUsingTestData(context)) {
            return loadReferenceRgb(context);
        }

        if (hasSavedReference(context)) {
            return loadSavedRgbReference(context);
        }

        return loadReferenceRgb(context);
    }

    public static File getSavedReferenceFile(Context context) {
        return new File(context.getFilesDir(), FILE_SAVED_REFERENCE);
    }

    public static File getRunsReferenceFile(Context context) {
        return new File(context.getFilesDir(), FILE_RUNS_REFERENCE);
    }

    public static File getRunsSavedFile(Context context) {
        return new File(context.getFilesDir(), FILE_RUNS_SAVED);
    }

    public static boolean hasSavedReference(Context context) {
        File file = getSavedReferenceFile(context);
        return file.exists() && file.length() > 0;
    }

    public static void clearSavedReference(Context context) {
        File file = getSavedReferenceFile(context);
        if (file.exists()) {
            boolean deleted = file.delete();
            Log.d(TAG, "clearSavedReference deleted=" + deleted);
        }
    }

    public static JSONObject loadReferenceJson(Context context) {
        try {
            String json = readAssetText(context, ASSET_REFERENCE_PATH);
            if (json == null || json.trim().isEmpty()) return null;
            return new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "loadReferenceJson failed", e);
            return null;
        }
    }

    public static JSONObject loadSavedReferenceJson(Context context) {
        try {
            File file = getSavedReferenceFile(context);
            if (!file.exists()) return null;

            String json = readFileText(file);
            if (json == null || json.trim().isEmpty()) return null;
            return new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "loadSavedReferenceJson failed", e);
            return null;
        }
    }

    public static float[] loadReferenceRgb(Context context) {
        try {
            JSONObject obj = loadReferenceJson(context);
            if (obj == null) return null;

            if (obj.has("rgb15")) {
                return jsonArrayToFloatArray(obj.getJSONArray("rgb15"));
            }
            if (obj.has("median_rgb15")) {
                return jsonArrayToFloatArray(obj.getJSONArray("median_rgb15"));
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "loadReferenceRgb failed", e);
            return null;
        }
    }

    public static float[] loadSavedRgbReference(Context context) {
        try {
            JSONObject obj = loadSavedReferenceJson(context);
            if (obj == null) return null;

            if (obj.has("rgb15")) {
                return jsonArrayToFloatArray(obj.getJSONArray("rgb15"));
            }
            if (obj.has("median_rgb15")) {
                return jsonArrayToFloatArray(obj.getJSONArray("median_rgb15"));
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "loadSavedRgbReference failed", e);
            return null;
        }
    }

    public static List<float[]> loadSavedRgbSamples(Context context) {
        try {
            JSONObject obj = loadSavedReferenceJson(context);
            if (obj == null || !obj.has("samples")) {
                return new ArrayList<>();
            }
            return jsonToFloat2dList(obj.getJSONArray("samples"));
        } catch (Exception e) {
            Log.e(TAG, "loadSavedRgbSamples failed", e);
            return new ArrayList<>();
        }
    }

    public static void saveSavedReference(Context context, float[] medianRgb15, int sampleCount, List<float[]> samples) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("version", 1);
            obj.put("source", "SAVED");
            obj.put("sample_n", sampleCount);
            obj.put("saved_time_ms", nowMs());
            obj.put("saved_time_iso", nowIso());
            obj.put("rgb15", new JSONArray(floatArrayToList(medianRgb15)));
            obj.put("samples", float2dListToJsonArray(samples));

            writeText(getSavedReferenceFile(context), obj.toString(2));
        } catch (Exception e) {
            Log.e(TAG, "saveSavedReference failed", e);
        }
    }

    public static void saveRun(
            Context context,
            float[] correctedRgb,
            Object evalResult,
            boolean calibrationApplied,
            String imageUri,
            JSONArray shapesJson
    ) {
        try {
            String source = getActiveReferenceSource(context);
            float[] refRgb = loadActiveReferenceRgb(context);

            JSONObject run = new JSONObject();
            run.put("version", 1);
            run.put("source", source);
            run.put("timestamp_ms", nowMs());
            run.put("timestamp_iso", nowIso());
            run.put("calibration_applied", calibrationApplied);
            run.put("image_uri", imageUri == null ? "" : imageUri);

            if (refRgb != null) {
                run.put("reference_rgb15", new JSONArray(floatArrayToList(refRgb)));
            } else {
                run.put("reference_rgb15", JSONObject.NULL);
            }

            if (correctedRgb != null) {
                run.put("corrected_rgb15", new JSONArray(floatArrayToList(correctedRgb)));
            } else {
                run.put("corrected_rgb15", JSONObject.NULL);
            }

            if (evalResult != null) {
                run.put("eval_result", String.valueOf(evalResult));
            } else {
                run.put("eval_result", JSONObject.NULL);
            }

            if (shapesJson != null) {
                run.put("shapes", shapesJson);
            } else {
                run.put("shapes", new JSONArray());
            }

            if ("SAVED".equals(source)) {
                appendJsonObjectToArrayFile(getRunsSavedFile(context), run);
            } else {
                appendJsonObjectToArrayFile(getRunsReferenceFile(context), run);
            }
        } catch (Exception e) {
            Log.e(TAG, "saveRun failed", e);
        }
    }

    public static float[] computeMedianRgb15(List<float[]> rgbList) {
        if (rgbList == null || rgbList.isEmpty()) return null;

        float[] out = new float[15];

        for (int i = 0; i < 15; i++) {
            List<Float> vals = new ArrayList<>();

            for (float[] row : rgbList) {
                if (row != null && row.length > i) {
                    vals.add(row[i]);
                }
            }

            if (vals.isEmpty()) {
                out[i] = 0f;
                continue;
            }

            Collections.sort(vals);
            int n = vals.size();
            if (n % 2 == 1) {
                out[i] = vals.get(n / 2);
            } else {
                out[i] = (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2f;
            }
        }

        return out;
    }

    private static void appendJsonObjectToArrayFile(File file, JSONObject obj) throws Exception {
        JSONArray arr;

        if (file.exists()) {
            String text = readFileText(file);
            arr = (text == null || text.trim().isEmpty()) ? new JSONArray() : new JSONArray(text);
        } else {
            arr = new JSONArray();
        }

        arr.put(obj);
        writeText(file, arr.toString(2));
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static String nowIso() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.KOREA);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        return sdf.format(new Date());
    }

    private static String readAssetText(Context context, String assetName) throws Exception {
        try (InputStream is = context.getAssets().open(assetName);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String readFileText(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileWriter fw = new FileWriter(file, false)) {
            fw.write(text);
            fw.flush();
        }
    }

    private static float[] jsonArrayToFloatArray(JSONArray ja) throws Exception {
        if (ja == null) return null;
        float[] out = new float[ja.length()];
        for (int i = 0; i < ja.length(); i++) {
            out[i] = (float) ja.getDouble(i);
        }
        return out;
    }

    private static List<float[]> jsonToFloat2dList(JSONArray outer) throws Exception {
        List<float[]> out = new ArrayList<>();
        if (outer == null) return out;

        for (int i = 0; i < outer.length(); i++) {
            JSONArray inner = outer.getJSONArray(i);
            float[] row = new float[inner.length()];
            for (int j = 0; j < inner.length(); j++) {
                row[j] = (float) inner.getDouble(j);
            }
            out.add(row);
        }
        return out;
    }

    private static JSONArray float2dListToJsonArray(List<float[]> list) throws JSONException {
        JSONArray outer = new JSONArray();
        if (list == null) return outer;

        for (float[] row : list) {
            JSONArray inner = new JSONArray();
            if (row != null) {
                for (float v : row) {
                    inner.put(v);
                }
            }
            outer.put(inner);
        }
        return outer;
    }

    private static List<Float> floatArrayToList(float[] arr) {
        List<Float> out = new ArrayList<>();
        if (arr != null) {
            for (float v : arr) out.add(v);
        }
        return out;
    }
}