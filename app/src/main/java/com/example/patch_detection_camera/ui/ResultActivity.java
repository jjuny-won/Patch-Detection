package com.example.patch_detection_camera.ui;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;

import java.util.Date;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class ResultActivity extends AppCompatActivity {

    private static final String TAG = "ResultActivity";

    private TextView valueSquare;
    private TextView valueTriangle;
    private TextView valueCircle;
    private TextView valueHexagon;

    private TextView valueSquareCorrected;
    private TextView valueTriangleCorrected;
    private TextView valueCircleCorrected;
    private TextView valueHexagonCorrected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        bindViews();

        // 업데이트 시간 표시
        TextView tvTime = findViewById(R.id.tvUpdatedTime);
        String formatted = DateFormat.format("MMM dd, yyyy hh:mm a", new Date()).toString();
        tvTime.setText("Updated " + formatted);

        String shapesJson   = getIntent().getStringExtra("shapes_json");
        String correctedJson = getIntent().getStringExtra("corrected_shapes_json");

        bindShapesBlock(shapesJson,    false);
        bindShapesBlock(correctedJson, true);

        findViewById(R.id.btnMonitoring).setOnClickListener(v ->
                Toast.makeText(this, "준비 중", Toast.LENGTH_SHORT).show()
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
        valueSquare   = findViewById(R.id.valueRect);       // xml id 그대로 유지
        valueTriangle = findViewById(R.id.valueTriangle);
        valueCircle   = findViewById(R.id.valueCircle);
        valueHexagon  = findViewById(R.id.valueHexagon);

        valueSquareCorrected   = findViewById(R.id.valueRectCorrected);
        valueTriangleCorrected = findViewById(R.id.valueTriangleCorrected);
        valueCircleCorrected   = findViewById(R.id.valueCircleCorrected);
        valueHexagonCorrected  = findViewById(R.id.valueHexagonCorrected);
    }

    /**
     * shapes_json 또는 corrected_shapes_json을 파싱해서
     * 도형 타입별로 TextView에 매핑합니다.
     */
    private void bindShapesBlock(String json, boolean corrected) {
        TextView sqView = corrected ? valueSquareCorrected   : valueSquare;
        TextView trView = corrected ? valueTriangleCorrected : valueTriangle;
        TextView ciView = corrected ? valueCircleCorrected   : valueCircle;
        TextView hxView = corrected ? valueHexagonCorrected  : valueHexagon;

        // 기본값
        sqView.setText("검출 실패");
        trView.setText("검출 실패");
        ciView.setText("검출 실패");
        hxView.setText("검출 실패");

        if (json == null) return;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);

                // corrected_shapes_json은 corr_r/g/b, shapes_json은 r/g/b
                int r = corrected ? o.optInt("corr_r", o.optInt("r", 0))
                        : o.optInt("r", 0);
                int g = corrected ? o.optInt("corr_g", o.optInt("g", 0))
                        : o.optInt("g", 0);
                int b = corrected ? o.optInt("corr_b", o.optInt("b", 0))
                        : o.optInt("b", 0);

                String rgb = formatRgb(r, g, b);
                String type = o.optString("type", "").toUpperCase(Locale.US);

                switch (type) {
                    case "RECTANGLE":
                    case "SQUARE":    sqView.setText(rgb); break;
                    case "TRIANGLE":  trView.setText(rgb); break;
                    case "CIRCLE":    ciView.setText(rgb); break;
                    case "HEXAGON":   hxView.setText(rgb); break;
                    default:
                        Log.w(TAG, "unknown type: " + type);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "bindShapesBlock error", e);
        }
    }

    private String formatRgb(int r, int g, int b) {
        return String.format(Locale.US, "(%d, %d, %d)",
                clamp255(r), clamp255(g), clamp255(b));
    }

    private int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}