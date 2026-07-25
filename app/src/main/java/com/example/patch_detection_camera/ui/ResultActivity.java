package com.example.patch_detection_camera.ui;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class ResultActivity extends AppCompatActivity {

    private static final String TAG = "ResultActivity";

    // 숨김 호환 뷰
    private TextView valueSquare;
    private TextView valueTriangle;
    private TextView valueCircle;
    private TextView valueHexagon;
    private TextView valueSquareCorrected;
    private TextView valueTriangleCorrected;
    private TextView valueCircleCorrected;
    private TextView valueHexagonCorrected;

    // 보이는 카드 뷰
    private TextView tvPhValue, tvPhStatus;
    private FrameLayout phBarContainer;
    private View phIndicator;

    private TextView tvTempValue, tvTempStatus;
    private FrameLayout tempBarContainer;
    private View tempIndicator;

    private TextView tvGlucoseValue, tvGlucoseStatus;
    private FrameLayout glucoseBarContainer;
    private View glucoseIndicator;

    private TextView tvUricValue, tvUricStatus;
    private FrameLayout uricBarContainer;
    private View uricIndicator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        bindViews();



        String shapesJson    = getIntent().getStringExtra("shapes_json");
        String correctedJson = getIntent().getStringExtra("corrected_shapes_json");

        bindShapesBlock(shapesJson,    false);
        bindShapesBlock(correctedJson, true);

        // Task 1: 보이는 카드 업데이트
        String displayJson = correctedJson != null ? correctedJson : shapesJson;
        float[] vals = parseBiomarkers(displayJson, correctedJson != null);
        updateVisibleCards(vals[0], vals[1], vals[2], vals[3]);

        saveToFirebase(shapesJson, correctedJson);

        findViewById(R.id.btnMonitoring).setOnClickListener(v ->
                startActivity(new Intent(this, WoundMonitoringActivity.class))
        );
        findViewById(R.id.btnDebug).setOnClickListener(v -> {
            Intent intent = new Intent(this, DebugActivity.class);
            intent.putExtra("rgb15",       getIntent().getFloatArrayExtra("rgb15"));
            intent.putExtra("shapes_json", shapesJson);
            startActivity(intent);
        });
        findViewById(R.id.btnHome).setOnClickListener(v -> {
            Intent it = new Intent(this, ModeSelectActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        });
    }

    private void bindViews() {
        valueSquare   = findViewById(R.id.valueRect);
        valueTriangle = findViewById(R.id.valueTriangle);
        valueCircle   = findViewById(R.id.valueCircle);
        valueHexagon  = findViewById(R.id.valueHexagon);

        valueSquareCorrected   = findViewById(R.id.valueRectCorrected);
        valueTriangleCorrected = findViewById(R.id.valueTriangleCorrected);
        valueCircleCorrected   = findViewById(R.id.valueCircleCorrected);
        valueHexagonCorrected  = findViewById(R.id.valueHexagonCorrected);

        tvPhValue       = findViewById(R.id.tvPhValue);
        tvPhStatus      = findViewById(R.id.tvPhStatus);
        phBarContainer  = findViewById(R.id.phBarContainer);
        phIndicator     = findViewById(R.id.phIndicator);

        tvTempValue       = findViewById(R.id.tvTempValue);
        tvTempStatus      = findViewById(R.id.tvTempStatus);
        tempBarContainer  = findViewById(R.id.tempBarContainer);
        tempIndicator     = findViewById(R.id.tempIndicator);

        tvGlucoseValue       = findViewById(R.id.tvGlucoseValue);
        tvGlucoseStatus      = findViewById(R.id.tvGlucoseStatus);
        glucoseBarContainer  = findViewById(R.id.glucoseBarContainer);
        glucoseIndicator     = findViewById(R.id.glucoseIndicator);

        tvUricValue       = findViewById(R.id.tvUricValue);
        tvUricStatus      = findViewById(R.id.tvUricStatus);
        uricBarContainer  = findViewById(R.id.uricBarContainer);
        uricIndicator     = findViewById(R.id.uricIndicator);
    }

    // ─── Task 1: JSON 파싱 (ph/temp/uric/gluc 0~1 정규화값 반환) ──────────────
    private float[] parseBiomarkers(String json, boolean corrected) {
        float ph = -1, temp = -1, uric = -1, gluc = -1;
        if (json == null) return new float[]{ph, temp, uric, gluc};
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int r = corrected ? o.optInt("corr_r", o.optInt("r", 0)) : o.optInt("r", 0);
                int g = corrected ? o.optInt("corr_g", o.optInt("g", 0)) : o.optInt("g", 0);
                int b = corrected ? o.optInt("corr_b", o.optInt("b", 0)) : o.optInt("b", 0);
                float val = (r + g + b) / (3.0f * 255.0f);
                switch (o.optString("type", "").toUpperCase(Locale.US)) {
                    case "RECTANGLE": case "SQUARE": ph   = val; break;
                    case "TRIANGLE":                 temp = val; break;
                    case "CIRCLE":                   uric = val; break;
                    case "HEXAGON":                  gluc = val; break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parseBiomarkers error", e);
        }
        return new float[]{ph, temp, uric, gluc};
    }

    // ─── Task 1: 보이는 카드 업데이트 ──────────────────────────────────────────
    private void updateVisibleCards(float phNorm, float tempNorm, float uricNorm, float glucNorm) {
        // pH: 0~1 → pH 4~10
        if (phNorm >= 0) {
            float ph = 4.0f + phNorm * 6.0f;
            tvPhValue.setText(String.format(Locale.US, "%.1f", ph));
            tvPhStatus.setText(phStatus(ph));
            positionIndicator(phBarContainer, phIndicator, phNorm);
        }

        // Temp: 0~1 → 30~42°C
        if (tempNorm >= 0) {
            float temp = 30.0f + tempNorm * 12.0f;
            tvTempValue.setText(String.format(Locale.US, "%.1f °C", temp));
            tvTempStatus.setText(temp < 37.5f ? "Normal" : "Elevated");
            positionIndicator(tempBarContainer, tempIndicator, tempNorm);
        }

// Uric Acid: 0~1 → 0~600 μmol/L
        if (uricNorm >= 0) {
            float uricVal = uricNorm * 600.0f;
            tvUricValue.setText(String.format(Locale.US, "%.0f μmol/L", uricVal));
            tvUricStatus.setText(uricVal < 300f ? "Normal" : "Elevated");
            positionIndicator(uricBarContainer, uricIndicator, uricNorm);
        }

// Glucose: 0~1 → 0~20 mmol/L
        if (glucNorm >= 0) {
            float glucVal = glucNorm * 20.0f;
            tvGlucoseValue.setText(String.format(Locale.US, "%.1f mmol/L", glucVal));
            tvGlucoseStatus.setText(glucVal < 4.0f ? "Normal" : "Elevated");
            positionIndicator(glucoseBarContainer, glucoseIndicator, glucNorm);
        }
    }

    private String phStatus(float ph) {
        if (ph < 5.5f)  return "Acidic (Healthy)";
        if (ph < 7.0f)  return "Normal";
        if (ph < 8.0f)  return "Mildly Alkaline";
        return "Elevated";
    }

    private void positionIndicator(FrameLayout container, View indicator, float norm) {
        container.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        int barW = container.getWidth();
                        int indW = indicator.getWidth();
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicator.getLayoutParams();
                        lp.leftMargin  = (int) (norm * (barW - indW));
                        lp.gravity     = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
                        indicator.setLayoutParams(lp);
                    }
                });
    }

    // ─── 기존 숨김 뷰 바인딩 ────────────────────────────────────────────────────
    private void bindShapesBlock(String json, boolean corrected) {
        TextView sqView = corrected ? valueSquareCorrected   : valueSquare;
        TextView trView = corrected ? valueTriangleCorrected : valueTriangle;
        TextView ciView = corrected ? valueCircleCorrected   : valueCircle;
        TextView hxView = corrected ? valueHexagonCorrected  : valueHexagon;

        sqView.setText("검출 실패");
        trView.setText("검출 실패");
        ciView.setText("검출 실패");
        hxView.setText("검출 실패");

        if (json == null) return;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int r = corrected ? o.optInt("corr_r", o.optInt("r", 0)) : o.optInt("r", 0);
                int g = corrected ? o.optInt("corr_g", o.optInt("g", 0)) : o.optInt("g", 0);
                int b = corrected ? o.optInt("corr_b", o.optInt("b", 0)) : o.optInt("b", 0);
                String rgb  = formatRgb(r, g, b);
                String type = o.optString("type", "").toUpperCase(Locale.US);
                switch (type) {
                    case "RECTANGLE": case "SQUARE": sqView.setText(rgb); break;
                    case "TRIANGLE":                 trView.setText(rgb); break;
                    case "CIRCLE":                   ciView.setText(rgb); break;
                    case "HEXAGON":                  hxView.setText(rgb); break;
                    default: Log.w(TAG, "unknown type: " + type);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "bindShapesBlock error", e);
        }
    }

    private void saveToFirebase(String shapesJson, String correctedJson) {
        String displayJson = correctedJson != null ? correctedJson : shapesJson;
        if (displayJson == null) return;

        boolean calibrated = correctedJson != null;
        Measurement m = new Measurement();
        m.capturedAt         = System.currentTimeMillis();
        m.createdAt          = m.capturedAt;
        m.calibrationApplied = calibrated;
        m.dressingId         = DressingStore.getActiveDressingId(this);

        // calibration quality fields
        String grade = getIntent().getStringExtra("grade");
        m.calibrationStatus  = calibrated ? (grade != null ? grade : "UNKNOWN") : "NONE";
        m.medianDeltaE       = getIntent().getFloatExtra("afterDeltaEMedian", 0f);
        m.maxDeltaE          = getIntent().getFloatExtra("afterDeltaEMax", 0f);

        try {
            JSONArray arr = new JSONArray(displayJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String type = o.optString("type", "").toUpperCase(Locale.US);
                if (type.isEmpty()) continue;

                SensorValue sv = new SensorValue();
                sv.rawR = o.optInt("raw_r", o.optInt("r", 0));
                sv.rawG = o.optInt("raw_g", o.optInt("g", 0));
                sv.rawB = o.optInt("raw_b", o.optInt("b", 0));
                sv.correctedR = o.optInt("corr_r", sv.rawR);
                sv.correctedG = o.optInt("corr_g", sv.rawG);
                sv.correctedB = o.optInt("corr_b", sv.rawB);

                float norm = (sv.correctedR + sv.correctedG + sv.correctedB) / (3.0f * 255.0f);

                switch (type) {
                    case "RECTANGLE": case "SQUARE":
                        sv.sensorType    = "pH";
                        sv.numericValue  = 4.0f + norm * 6.0f;
                        sv.unit          = "pH";
                        sv.statusCode    = phStatusCode(sv.numericValue);
                        sv.statusLabel   = phStatusLabel(sv.numericValue);
                        sv.predictedValue = String.format(Locale.US, "pH_%.1f", sv.numericValue);
                        break;
                    case "TRIANGLE":
                        sv.sensorType    = "Temperature";
                        sv.numericValue  = 30.0f + norm * 12.0f;
                        sv.unit          = "°C";
                        sv.statusCode    = sv.numericValue < 37.5f ? "NORMAL" : "ELEVATED";
                        sv.statusLabel   = sv.numericValue < 37.5f ? "정상" : "상승";
                        sv.predictedValue = String.format(Locale.US, "%.1f°C", sv.numericValue);
                        break;
                    case "CIRCLE":
                        sv.sensorType    = "UricAcid";
                        sv.numericValue  = norm * 600.0f;
                        sv.unit          = "μmol/L";
                        sv.statusCode    = sv.numericValue < 300f ? "NORMAL" : "ELEVATED";
                        sv.statusLabel   = sv.numericValue < 300f ? "정상" : "상승";
                        sv.predictedValue = String.format(Locale.US, "%.0f μmol/L", sv.numericValue);
                        break;
                    case "HEXAGON":
                        sv.sensorType    = "Glucose";
                        sv.numericValue  = norm * 20.0f;
                        sv.unit          = "mmol/L";
                        sv.statusCode    = sv.numericValue < 4.0f ? "NORMAL" : "ELEVATED";
                        sv.statusLabel   = sv.numericValue < 4.0f ? "정상" : "상승";
                        sv.predictedValue = String.format(Locale.US, "%.1f mmol/L", sv.numericValue);
                        break;
                    default:
                        continue;
                }
                m.sensors.add(sv);
            }
        } catch (Exception e) {
            Log.e(TAG, "saveToFirebase parse error", e);
        }

        float[] rgb15      = getIntent().getFloatArrayExtra("rgb15");
        float[] corrRgb15  = getIntent().getFloatArrayExtra("correctedRgb15");
        if (rgb15 != null && rgb15.length >= 15) {
            String[] types = {"GRAY", "RED", "GREEN", "BLUE", "WHITE"};
            for (int i = 0; i < 5; i++) {
                ColorMarkerValue cmv = new ColorMarkerValue();
                cmv.colorType  = types[i];
                cmv.rawR       = (int) rgb15[i * 3];
                cmv.rawG       = (int) rgb15[i * 3 + 1];
                cmv.rawB       = (int) rgb15[i * 3 + 2];
                if (corrRgb15 != null && corrRgb15.length >= 15) {
                    cmv.correctedR = (int) corrRgb15[i * 3];
                    cmv.correctedG = (int) corrRgb15[i * 3 + 1];
                    cmv.correctedB = (int) corrRgb15[i * 3 + 2];
                } else {
                    cmv.correctedR = cmv.rawR;
                    cmv.correctedG = cmv.rawG;
                    cmv.correctedB = cmv.rawB;
                }
                m.colorMarkers.add(cmv);
            }
        }

        if (m.sensors.isEmpty()) return;

        String imgUri = getIntent().getStringExtra("image_uri");
        if (imgUri != null && !imgUri.isEmpty()) {
            uploadImageThenSave(m, imgUri);
        } else {
            persistMeasurement(m);
        }
    }

    private void uploadImageThenSave(Measurement m, String imgUri) {
        try {
            Uri uri = Uri.parse(imgUri);
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) { persistMeasurement(m); return; }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
            is.close();
            byte[] bytes = buf.toByteArray();

            StorageReference ref = FirebaseStorage.getInstance()
                    .getReference("images/" + m.capturedAt + ".jpg");
            ref.putBytes(bytes)
                    .continueWithTask(task -> {
                        if (!task.isSuccessful()) throw task.getException();
                        return ref.getDownloadUrl();
                    })
                    .addOnSuccessListener(url -> {
                        m.imageUrl = url.toString();
                        persistMeasurement(m);
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Storage upload failed, saving without imageUrl", e);
                        persistMeasurement(m);
                    });
        } catch (Exception e) {
            Log.w(TAG, "uploadImageThenSave error", e);
            persistMeasurement(m);
        }
    }

    private void persistMeasurement(Measurement m) {
        FirebaseRepository.getInstance().addMeasurement(m, new FirebaseRepository.SimpleCallback() {
            @Override public void onSuccess() {
                Toast.makeText(ResultActivity.this, "측정값이 저장되었습니다.", Toast.LENGTH_SHORT).show();
            }
            @Override public void onFailure(Exception e) {
                Log.e(TAG, "Firebase save failed", e);
            }
        });
    }

    private String phStatusCode(float ph) {
        if (ph < 5.5f) return "ACIDIC_HEALTHY";
        if (ph < 7.0f) return "NORMAL";
        if (ph < 8.0f) return "MILDLY_ALKALINE";
        return "ELEVATED";
    }

    private String phStatusLabel(float ph) {
        if (ph < 5.5f) return "산성 (정상)";
        if (ph < 7.0f) return "정상";
        if (ph < 8.0f) return "약알칼리성";
        return "상승";
    }

    private String formatRgb(int r, int g, int b) {
        return String.format(Locale.US, "(%d, %d, %d)", clamp255(r), clamp255(g), clamp255(b));
    }

    private int clamp255(int v) { return Math.max(0, Math.min(255, v)); }
}
