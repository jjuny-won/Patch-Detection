package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;
import com.example.patch_detection_camera.processing.ReferenceStore;
import com.example.patch_detection_camera.processing.ShapeConsistencyEvaluator;
import com.example.patch_detection_camera.processing.calibration.ColorCalibrator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DebugActivity extends AppCompatActivity {

    private static final String TAG = "DebugActivity";

    private TextView tvUpdatedTime;

    private TextView valueCircle;
    private TextView valueCircleCorrected;

    private TextView valueRect;
    private TextView valueRectCorrected;

    private TextView valueTriangle;
    private TextView valueTriangleCorrected;

    private TextView valueHexagon;
    private TextView valueHexagonCorrected;

    private ShapeConsistencyEvaluator.Result sc;
    private String shapesJsonStr;
    private float[] curMarker;
    private float[] refMarker;

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

        bindViews();
        bindButtons();
        setUpdatedTime();

        shapesJsonStr = getIntent().getStringExtra("shapes_json");
        curMarker = getIntent().getFloatArrayExtra("rgb15");
        refMarker = ReferenceStore.loadActiveReferenceRgb(this);

        parseShapes(shapesJsonStr);

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
                try {
                    File runsFile;

                    // 현재 사용 기준에 따라 파일 선택
                    if ("SAVED".equals(ReferenceStore.getActiveReferenceSource(this))) {
                        runsFile = ReferenceStore.getRunsSavedFile(this);
                    } else {
                        runsFile = ReferenceStore.getRunsReferenceFile(this);
                    }

                    if (runsFile.exists()) {
                        sc = ShapeConsistencyEvaluator.evaluate(runsFile, 10);
                        Log.d(TAG, "ShapeConsistency nLab=" + sc.nLab);
                    } else {
                        Log.d(TAG, "runs file not found");
                        sc = null;
                    }

                } catch (Exception e) {
                    Log.e(TAG, "ShapeConsistency eval failed", e);
                    sc = null;
                }
            }
        }

        bindShapeCards();
    }

    private void bindViews() {
        tvUpdatedTime = findViewById(R.id.tvUpdatedTime);

        valueCircle = findViewById(R.id.valueCircle);
        valueCircleCorrected = findViewById(R.id.valueCircleCorrected);

        valueRect = findViewById(R.id.valueRect);
        valueRectCorrected = findViewById(R.id.valueRectCorrected);

        valueTriangle = findViewById(R.id.valueTriangle);
        valueTriangleCorrected = findViewById(R.id.valueTriangleCorrected);

        valueHexagon = findViewById(R.id.valueHexagon);
        valueHexagonCorrected = findViewById(R.id.valueHexagonCorrected);
    }

    private void bindButtons() {
        Button btnMonitoring = findViewById(R.id.btnMonitoring);
        Button btnDebug = findViewById(R.id.btnDebug);
        Button btnHome = findViewById(R.id.btnHome);

        btnMonitoring.setOnClickListener(v ->
                Toast.makeText(this, "준비 중", Toast.LENGTH_SHORT).show());

        // "Result" 버튼 → ResultActivity로 back
        btnDebug.setOnClickListener(v -> finish());

        btnHome.setOnClickListener(v -> {
            Intent it = new Intent(this, ModeSelectActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        });

        // "Color Marker" 칩 → ColorMarkerActivity
        findViewById(R.id.tvChip).setOnClickListener(v -> {
            Intent it = new Intent(this, ColorMarkerActivity.class);
            it.putExtra("rgb15", curMarker);
            startActivity(it);
        });
    }

    private void setUpdatedTime() {
        String formatted = DateFormat.format("MMM dd, yyyy hh:mm a", new Date()).toString();
        tvUpdatedTime.setText("Updated " + formatted);
    }

    private void bindShapeCards() {
        setAllDefault();

        for (ShapeItem s : shapes) {
            int rawR = s.r;
            int rawG = s.g;
            int rawB = s.b;

            String rawText = formatRgb(rawR, rawG, rawB);
            String correctedText = rawText;

            if (fit != null && fit.ok) {
                int[] corr = ColorCalibrator.applyModelToRgb(rawR, rawG, rawB, fit);
                correctedText = formatRgb(corr[0], corr[1], corr[2]);
            }

            String type = normalizeType(s.type);

            switch (type) {
                case "CIRCLE":
                    valueCircle.setText(rawText);
                    valueCircleCorrected.setText(correctedText);
                    break;

                case "SQUARE":
                case "RECT":
                case "RECTANGLE":
                    valueRect.setText(rawText);
                    valueRectCorrected.setText(correctedText);
                    break;

                case "TRIANGLE":
                    valueTriangle.setText(rawText);
                    valueTriangleCorrected.setText(correctedText);
                    break;

                case "HEXAGON":
                    valueHexagon.setText(rawText);
                    valueHexagonCorrected.setText(correctedText);
                    break;

                default:
                    Log.w(TAG, "Unknown shape type: " + s.type);
                    break;
            }
        }

        showFailureIfMissing();
    }

    private void setAllDefault() {
        valueCircle.setText("-");
        valueCircleCorrected.setText("-");

        valueRect.setText("-");
        valueRectCorrected.setText("-");

        valueTriangle.setText("-");
        valueTriangleCorrected.setText("-");

        valueHexagon.setText("-");
        valueHexagonCorrected.setText("-");
    }

    private void showFailureIfMissing() {
        if (valueTriangle.getText().toString().equals("-")) {
            valueTriangle.setText("검출 실패");
            valueTriangleCorrected.setText("검출 실패");
        }
        if (valueCircle.getText().toString().equals("-")) {
            valueCircle.setText("검출 실패");
            valueCircleCorrected.setText("검출 실패");
        }
        if (valueRect.getText().toString().equals("-")) {
            valueRect.setText("검출 실패");
            valueRectCorrected.setText("검출 실패");
        }
        if (valueHexagon.getText().toString().equals("-")) {
            valueHexagon.setText("검출 실패");
            valueHexagonCorrected.setText("검출 실패");
        }
    }

    private static String normalizeType(String type) {
        if (type == null) return "UNKNOWN";
        return type.trim().toUpperCase(Locale.US);
    }

    private static String formatRgb(int r, int g, int b) {
        return String.format(Locale.US, "( %d, %d, %d )", r, g, b);
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
            } catch (Exception e) {
                Log.e(TAG, "buildCorrectedShapesJson error", e);
            }
        }

        return arr;
    }

    private void parseShapes(String s) {
        shapes.clear();
        if (s == null || s.trim().isEmpty()) return;

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

    private static boolean markerImproved(ColorCalibrator.EvalResult ev) {
        if (ev == null) return false;
        if (!Double.isFinite(ev.beforeDeltaEMedian) || !Double.isFinite(ev.afterDeltaEMedian)) {
            return false;
        }

        double diff = ev.beforeDeltaEMedian - ev.afterDeltaEMedian;
        boolean medianImproved = diff >= 0.30;
        boolean maxNotWorse = Double.isFinite(ev.beforeDeltaEMax) && Double.isFinite(ev.afterDeltaEMax)
                ? (ev.afterDeltaEMax <= ev.beforeDeltaEMax + 0.01)
                : true;

        return medianImproved && maxNotWorse;
    }
}