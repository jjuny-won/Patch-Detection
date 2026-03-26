package com.example.patch_detection_camera.ui;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import com.example.patch_detection_camera.processing.ShapeConsistencyEvaluator;
import java.io.File;
import com.example.patch_detection_camera.R;
import com.example.patch_detection_camera.processing.calibration.ColorCalibrator;
import com.example.patch_detection_camera.processing.ReferenceStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DebugActivity extends AppCompatActivity {
    private static final String TAG = "DebugActivity";

    private TextView tv;
    private ShapeConsistencyEvaluator.Result sc;
    private String shapesJsonStr;
    private float[] curMarker;     // from intent (current marker rgb15)
    private float[] refMarker;     // from storage (reference rgb15)

    private ColorCalibrator.Model fit;
    private ColorCalibrator.EvalResult eval;
    private final List<ShapeItem> shapes = new ArrayList<>();

    static class ShapeItem {
        String type;
        int r, g, b;
        double area;
        int x, y, w, h;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        tv = findViewById(R.id.tvResultText);

        shapesJsonStr = getIntent().getStringExtra("shapes_json");
        curMarker = getIntent().getFloatArrayExtra("rgb15");

        parseShapes(shapesJsonStr);

        refMarker = ReferenceStore.loadRgbReference(this);

        fit = null;
        eval = null;

        boolean markerOk = false;

        if (curMarker != null && refMarker != null) {

            fit = ColorCalibrator.fitGammaWbCcmFromMarker(curMarker, refMarker);

            if (fit != null && fit.ok) {

                eval = ColorCalibrator.evaluateWithModel(curMarker, refMarker, fit);
                markerOk = markerImproved(eval);


                JSONArray correctedShapes = buildCorrectedShapesJson();

                ReferenceStore.saveRun(
                        this,
                        curMarker,
                        eval,
                        markerOk,
                        shapesJsonStr,
                        correctedShapes
                );

                // 3) 저장된 runs.json 기반으로 다시 계산 (이번 run 포함)
                try {
                    File runsFile = ReferenceStore.getRunsFile(this);
                    sc = ShapeConsistencyEvaluator.evaluate(runsFile, 10);
                    Log.d(TAG, "ShapeConsistency nLab=" + sc.nLab);
                } catch (Exception e) {
                    Log.e(TAG, "ShapeConsistency eval failed", e);
                    sc = null;
                }
            }
        }

        renderText(true);
    }
    private JSONArray buildCorrectedShapesJson() {
        JSONArray arr = new JSONArray();

        if (fit == null || !fit.ok) return arr;

        for (ShapeItem s : shapes) {
            try {
                JSONObject o = new JSONObject();

                o.put("type", s.type);
                o.put("raw_r", s.r);
                o.put("raw_g", s.g);
                o.put("raw_b", s.b);

                int[] corr = ColorCalibrator.applyModelToRgb(s.r, s.g, s.b, fit);

                o.put("corr_r", corr[0]);
                o.put("corr_g", corr[1]);
                o.put("corr_b", corr[2]);

                arr.put(o);
            } catch (Exception ignored) {}
        }

        return arr;
    }

    private void parseShapes(String s) {
        shapes.clear();
        if (s == null) return;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ShapeItem it = new ShapeItem();
                it.type = o.optString("type", "UNKNOWN");
                it.r = o.optInt("r", 0);
                it.g = o.optInt("g", 0);
                it.b = o.optInt("b", 0);
                it.area = o.optDouble("area", 0.0);
                it.x = o.optInt("x", 0);
                it.y = o.optInt("y", 0);
                it.w = o.optInt("w", 0);
                it.h = o.optInt("h", 0);
                shapes.add(it);
            }
        } catch (Exception e) {
            Log.e(TAG, "parseShapes error", e);
        }
    }

    private void renderText(boolean corrected) {
        StringBuilder sb = new StringBuilder();

        sb.append("MODE: ").append(corrected ? "CORRECTED" : "RAW").append("\n\n");

        // ----------------------------
        // Marker quality (RMSE + ΔE)
        // ----------------------------
        if (refMarker == null) {
            sb.append("Reference: NOT FOUND\n\n");
        } else if (fit == null || !fit.ok) {
            sb.append("Calib: FAILED\n");
            sb.append(" - reason: ").append(fit != null ? fit.error : "null").append("\n\n");
        } else if (eval == null) {
            sb.append("Calib: FAILED\n");
            sb.append(" - reason: eval null\n\n");
        } else {
            sb.append("Calib: OK (3x3)\n");
            sb.append(" - RMSE Improve(%): ").append(String.format("%.1f", eval.improvementPct)).append("%\n");
            if (eval.reason != null && !eval.reason.isEmpty()) sb.append(" (").append(eval.reason).append(")");
            sb.append("\n");

            sb.append(" - MarkerImproved: ")
                    .append(markerImproved(eval) ? "YES ✅" : "NO ❌")
                    .append("\n\n");

            sb.append(" - BEFORE ΔE76 (mean/med/max): ")
                    .append(String.format("%.2f", eval.beforeDeltaEMean)).append(" / ")
                    .append(String.format("%.2f", eval.beforeDeltaEMedian)).append(" / ")
                    .append(String.format("%.2f", eval.beforeDeltaEMax)).append("\n");

            sb.append(" - AFTER  ΔE76 (mean/med/max): ")
                    .append(String.format("%.2f", eval.afterDeltaEMean)).append(" / ")
                    .append(String.format("%.2f", eval.afterDeltaEMedian)).append(" / ")
                    .append(String.format("%.2f", eval.afterDeltaEMax)).append("\n\n");

            appendPerPatchDeltaE(sb);
            // ----------------------------
// Shape consistency (runs.json lastK)
// ----------------------------
            sb.append("Shape Consistency (lastK)\n");
            if (sc == null || sc.nLab <= 0) {
                sb.append(" - not enough runs\n\n");
            } else {
                sb.append(String.format(" - nLab=%d\n", sc.nLab));
                sb.append(String.format(" - σ(L,a,b)=%.2f / %.2f / %.2f\n", sc.sigmaL, sc.sigmaA, sc.sigmaB));
                sb.append(String.format(" - pairwise ΔE(mean/max)=%.2f / %.2f\n\n", sc.pairwiseDeltaEMean, sc.pairwiseDeltaEMax));
            }
            sb.append("\n");
        }

        // ----------------------------
        // Reference meta
        // ----------------------------
        JSONObject meta = ReferenceStore.loadMeta(this);
        if (refMarker != null && meta != null) {
            int n = meta.optInt("sample_n", meta.optInt("sampleCount", -1));
            long t = meta.optLong("saved_time_ms", meta.optLong("updatedAt", 0L));

            sb.append("Reference Meta\n");
            sb.append(" - ref sampleN: ").append(n).append("\n");
            sb.append(" - saved: ").append(
                    android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", t)
            ).append("\n");
            sb.append(" - path: ").append(ReferenceStore.getReferencePath(this)).append("\n\n");
        }

        // ----------------------------
        // Shapes
        // ----------------------------
        if (shapes.isEmpty()) {
            sb.append("No shapes detected.\n");
            tv.setText(sb.toString());
            return;
        }

        for (int i = 0; i < shapes.size(); i++) {
            ShapeItem s = shapes.get(i);

            int rr = s.r, gg = s.g, bb = s.b;
            if (corrected && fit != null && fit.ok) {
                int[] out = ColorCalibrator.applyModelToRgb(s.r, s.g, s.b, fit);
                rr = out[0];
                gg = out[1];
                bb = out[2];
            }

            sb.append(i).append(") ").append(s.type).append("\n");
            sb.append("  RGB: ").append(rr).append(", ").append(gg).append(", ").append(bb);
            if (corrected && fit != null && fit.ok) {
                sb.append("   (raw ").append(s.r).append(",").append(s.g).append(",").append(s.b).append(")");
            }
            sb.append("\n");
        }

        tv.setText(sb.toString());
    }

    /**
     * marker(rgb15 or rgb45)의 patch별 ΔE76를 출력
     * - BEFORE: curMarker vs refMarker
     * - AFTER : correctedMarker vs refMarker   (correctedMarker는 fit model 적용)
     *
     * 추가로 worst patch(ΔE max)도 표시해줌.
     */
    private void appendPerPatchDeltaE(StringBuilder sb) {
        if (curMarker == null || refMarker == null || fit == null || !fit.ok) return;

        int len = Math.min(curMarker.length, refMarker.length);
        if (len < 15 || (len % 3) != 0) return;

        int N = len / 3;

        // BEFORE per-patch ΔE: cur vs ref
        double[] deBefore = ColorCalibrator.deltaEPerPatch(curMarker, refMarker);

        // AFTER per-patch ΔE: corrected(cur) vs ref
        float[] correctedMarker = ColorCalibrator.applyModelToMarker(curMarker, fit);
        double[] deAfter = (correctedMarker != null)
                ? ColorCalibrator.deltaEPerPatch(correctedMarker, refMarker)
                : null;

        sb.append("Per-patch ΔE76\n");

        int worstIdxBefore = -1;
        double worstBefore = -1;

        int worstIdxAfter = -1;
        double worstAfter = -1;

        for (int i = 0; i < N; i++) {
            double b = (deBefore != null && i < deBefore.length) ? deBefore[i] : Double.NaN;
            double a = (deAfter != null && i < deAfter.length) ? deAfter[i] : Double.NaN;

            if (Double.isFinite(b) && b > worstBefore) { worstBefore = b; worstIdxBefore = i; }
            if (Double.isFinite(a) && a > worstAfter) { worstAfter = a; worstIdxAfter = i; }

            int cr = Math.round(curMarker[i * 3]);
            int cg = Math.round(curMarker[i * 3 + 1]);
            int cb = Math.round(curMarker[i * 3 + 2]);

            int rr = Math.round(refMarker[i * 3]);
            int rg = Math.round(refMarker[i * 3 + 1]);
            int rb = Math.round(refMarker[i * 3 + 2]);

            int ar = cr, ag = cg, ab = cb;
            if (correctedMarker != null) {
                ar = Math.round(correctedMarker[i * 3]);
                ag = Math.round(correctedMarker[i * 3 + 1]);
                ab = Math.round(correctedMarker[i * 3 + 2]);
            }

            sb.append(String.format(" - P%02d: ΔE before=%.2f  after=%.2f\n", i, b, a));
            sb.append(String.format("        cur=(%d,%d,%d)  corr=(%d,%d,%d)  ref=(%d,%d,%d)\n",
                    cr, cg, cb, ar, ag, ab, rr, rg, rb));
        }

        if (worstIdxAfter >= 0) {
            sb.append(String.format("\nWorst AFTER: P%02d (ΔE=%.2f)\n", worstIdxAfter, worstAfter));
        }
        if (worstIdxBefore >= 0) {
            sb.append(String.format("Worst BEFORE: P%02d (ΔE=%.2f)\n", worstIdxBefore, worstBefore));
        }
    }

    private static boolean markerImproved(ColorCalibrator.EvalResult ev) {
        if (ev == null) return false;
        if (!Double.isFinite(ev.beforeDeltaEMedian) || !Double.isFinite(ev.afterDeltaEMedian)) return false;

        double diff = ev.beforeDeltaEMedian - ev.afterDeltaEMedian;
        boolean medianImproved = diff >= 0.30; // 너무 작은 변화는 노이즈일 수 있음
        boolean maxNotWorse = Double.isFinite(ev.beforeDeltaEMax) && Double.isFinite(ev.afterDeltaEMax)
                ? (ev.afterDeltaEMax <= ev.beforeDeltaEMax + 0.01)
                : true;

        return medianImproved && maxNotWorse;
    }
}