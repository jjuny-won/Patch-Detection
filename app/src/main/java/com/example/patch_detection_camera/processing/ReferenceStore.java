package com.example.patch_detection_camera.processing;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.example.patch_detection_camera.processing.calibration.ColorCalibrator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class ReferenceStore {
    private static final String TAG = "ReferenceStore";

    // reference file
    private static final String DIR_NAME = "patch_detection";
    private static final String FILE_NAME = "reference_rgb.json";

    private static final String RUN_DIR_NAME = "runs";

    // ----------------------------
    // Save reference (overwrite)
    // ----------------------------
    public static void saveRgbReference(Context ctx, float[] refRgb15, int sampleN, List<float[]> samples) {
        if (refRgb15 == null || refRgb15.length <= 0) {
            throw new IllegalArgumentException("refRgb15 is null/empty");
        }

        try {
            File f = getRefFile(ctx);

            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("len", refRgb15.length);
            root.put("sample_n", sampleN);
            root.put("saved_time_ms", System.currentTimeMillis());

            JSONArray rgbArr = new JSONArray();
            for (float v : refRgb15) rgbArr.put(Math.round(v));
            root.put("rgb15", rgbArr);

            if (samples != null) {
                JSONArray samplesArr = new JSONArray();
                for (float[] s : samples) {
                    if (s == null) continue;
                    JSONArray one = new JSONArray();
                    for (float v : s) one.put(Math.round(v));
                    samplesArr.put(one);
                }
                root.put("samples", samplesArr);
            }

            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (FileWriter w = new FileWriter(f, false)) {
                w.write(root.toString());
            }

            // verify
            float[] loaded = loadRgbReference(ctx);
            if (loaded == null || loaded.length != refRgb15.length) {
                throw new IllegalStateException("save verification failed (null/len mismatch)");
            }
            double maxAbs = maxAbsDiffRounded(refRgb15, loaded);
            if (maxAbs > 1e-6) {
                throw new IllegalStateException("save verification failed (diff=" + maxAbs + ")");
            }

            Log.d(TAG, "Saved ref to: " + f.getAbsolutePath()
                    + " len=" + refRgb15.length + " n=" + sampleN);

        } catch (Exception e) {
            Log.e(TAG, "saveRgbReference error", e);
            throw new RuntimeException(e);
        }
    }

    public static void saveRgbReference(Context ctx, float[] refRgb15, int sampleN) {
        saveRgbReference(ctx, refRgb15, sampleN, null);
    }

    // ----------------------------
    // Load reference
    // ----------------------------
    public static float[] loadRgbReference(Context ctx) {
        try {
            File f = getRefFile(ctx);
            if (!f.exists()) return null;

            String json = readAll(f);
            JSONObject root = new JSONObject(json);

            JSONArray arr = root.optJSONArray("rgb15");
            if (arr == null) arr = root.optJSONArray("data"); // old compat

            int len = root.optInt("len", -1);
            if (arr == null) return null;
            if (len <= 0) len = arr.length();
            if (arr.length() != len) return null;

            float[] out = new float[len];
            for (int i = 0; i < len; i++) out[i] = (float) arr.getDouble(i);
            return out;

        } catch (Exception e) {
            Log.e(TAG, "loadRgbReference error", e);
            return null;
        }
    }

    public static boolean hasReference(Context ctx) {
        File f = getRefFile(ctx);
        return f.exists() && f.length() > 0;
    }

    public static JSONObject loadMeta(Context ctx) {
        try {
            File f = getRefFile(ctx);
            if (!f.exists()) return null;
            return new JSONObject(readAll(f));
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearSavedReference(Context ctx) {
        try {
            File f = getRefFile(ctx);
            if (f.exists()) {
                boolean ok = f.delete();
                Log.d(TAG, "clearSavedReference: " + ok);
            }
        } catch (Exception e) {
            Log.e(TAG, "clearSavedReference error", e);
        }
    }

    public static String getReferencePath(Context ctx) {
        return getRefFile(ctx).getAbsolutePath();
    }

    // ----------------------------
    // Append samples + recompute median
    // ----------------------------
    public static void appendAndUpdateReference(Context ctx, List<float[]> newSamples) {
        try {
            List<float[]> allSamples = new ArrayList<>();

            // 기존 samples 불러오기
            JSONObject old = loadMeta(ctx);
            if (old != null) {
                JSONArray oldSamples = old.optJSONArray("samples");
                if (oldSamples != null) {
                    for (int i = 0; i < oldSamples.length(); i++) {
                        JSONArray one = oldSamples.getJSONArray(i);
                        float[] s = new float[one.length()];
                        for (int k = 0; k < one.length(); k++) {
                            s[k] = (float) one.getDouble(k);
                        }
                        allSamples.add(s);
                    }
                }
            }

            // 새 samples 추가
            if (newSamples != null) {
                for (float[] s : newSamples) {
                    if (s != null) allSamples.add(s.clone());
                }
            }

            if (allSamples.isEmpty()) return;

            float[] newMedian = computeMedianRgb15(allSamples);
            saveRgbReference(ctx, newMedian, allSamples.size(), allSamples);

            Log.d(TAG, "Reference updated. Total samples=" + allSamples.size());

        } catch (Exception e) {
            Log.e(TAG, "appendAndUpdateReference error", e);
        }
    }

    // ----------------------------
    // ✅ Runs 저장/로드 (shape consistency용)
    // ----------------------------

    /** 한 번 실행 결과(run) 저장: marker + shapes_json + timestamp */
    public static void saveRun(Context ctx, float[] markerRgb, String shapesJsonStr) {
        try {
            File dir = getRunDir(ctx);
            if (!dir.exists()) dir.mkdirs();

            long t = System.currentTimeMillis();
            File f = new File(dir, "run_" + t + ".json");

            JSONObject root = new JSONObject();
            root.put("time_ms", t);

            if (markerRgb != null) {
                JSONArray m = new JSONArray();
                for (float v : markerRgb) m.put(Math.round(v));
                root.put("rgb15", m);
            }

            // shapesJsonStr은 이미 JSONArray string 이니까 그대로 저장
            // (예: "[{type:..}, ...]")
            root.put("shapes_json", shapesJsonStr != null ? shapesJsonStr : "[]");

            try (FileWriter w = new FileWriter(f, false)) {
                w.write(root.toString());
            }

            Log.d(TAG, "Saved run: " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "saveRun error", e);
        }
    }

    /** 저장된 run 파일 목록 */
    public static List<File> listRuns(Context ctx) {
        List<File> out = new ArrayList<>();
        File dir = getRunDir(ctx);
        if (!dir.exists()) return out;

        File[] files = dir.listFiles((d, name) -> name != null && name.startsWith("run_") && name.endsWith(".json"));
        if (files == null) return out;

        // 최신순 정렬(간단)
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) out.add(f);
        return out;
    }

    public static JSONObject loadRunJson(File f) {
        try {
            return new JSONObject(readAll(f));
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------
    // Median helper
    // ----------------------------
    public static float[] computeMedianRgb15(List<float[]> samples) {
        if (samples == null || samples.isEmpty()) return null;

        int L = samples.get(0).length;
        for (float[] s : samples) {
            if (s == null || s.length != L) {
                throw new IllegalArgumentException("Sample length mismatch");
            }
        }

        float[] out = new float[L];
        float[] tmp = new float[samples.size()];

        for (int i = 0; i < L; i++) {
            for (int k = 0; k < samples.size(); k++) tmp[k] = samples.get(k)[i];
            java.util.Arrays.sort(tmp);
            int mid = tmp.length / 2;
            out[i] = (tmp.length % 2 == 1) ? tmp[mid] : (tmp[mid - 1] + tmp[mid]) / 2f;
        }
        return out;
    }

    // ----------------------------
    // File path utils
    // ----------------------------
    private static File getRefFile(Context ctx) {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(base, DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE_NAME);
    }

    private static File getRunDir(Context ctx) {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(new File(base, DIR_NAME), RUN_DIR_NAME);
        return dir;
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static double maxAbsDiffRounded(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double m = 0.0;
        for (int i = 0; i < n; i++) {
            double aa = Math.round(a[i]);
            double bb = b[i];
            m = Math.max(m, Math.abs(aa - bb));
        }
        return m;
    }

    private static final String RUN_FILE = "runs.json";

    public static void saveRun(
            Context ctx,
            float[] curMarker,
            ColorCalibrator.EvalResult eval,
            boolean markerOk,
            String shapesRawJson,
            JSONArray shapesCorrected
    ) {
        try {

            File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(base, DIR_NAME);
            if (!dir.exists()) dir.mkdirs();

            File f = new File(dir, RUN_FILE);

            JSONArray root;

            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                root = new JSONArray(sb.toString());
            } else {
                root = new JSONArray();
            }

            JSONObject run = new JSONObject();
            run.put("timestamp", System.currentTimeMillis());
            run.put("markerImproved", markerOk);

            if (eval != null) {
                run.put("markerBeforeMedian", eval.beforeDeltaEMedian);
                run.put("markerAfterMedian", eval.afterDeltaEMedian);
            }

            run.put("shapesRaw", new JSONArray(shapesRawJson));
            run.put("shapesCorrected", shapesCorrected);

            root.put(run);

            FileWriter w = new FileWriter(f, false);
            w.write(root.toString());
            w.close();

        } catch (Exception e) {
            Log.e(TAG, "saveRun error", e);
        }
    }

    // ReferenceStore.java 안에 추가
    public static File getRunsFile(Context ctx) {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(base, "patch_detection");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "runs.json");
    }
}