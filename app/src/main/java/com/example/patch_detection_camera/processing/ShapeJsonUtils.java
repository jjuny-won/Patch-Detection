package com.example.patch_detection_camera.processing;

import com.example.patch_detection_camera.processing.calibration.ColorCalibrator;
import com.example.patch_detection_camera.processing.detection.shape.ShapeGeometryClassifier;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.core.Rect;

import java.util.List;

public class ShapeJsonUtils {

    public static String toJson(List<ShapeGeometryClassifier.ShapeCandidate> list) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr.toString();

        for (ShapeGeometryClassifier.ShapeCandidate c : list) {
            if (c == null) continue;

            try {
                JSONObject o = new JSONObject();
                Rect b = (c.bbox != null) ? c.bbox : new Rect();

                o.put("type", c.type != null ? c.type.name() : "UNKNOWN");
                o.put("r", clamp255((int) Math.round(c.meanR)));
                o.put("g", clamp255((int) Math.round(c.meanG)));
                o.put("b", clamp255((int) Math.round(c.meanB)));

                double area = (b.width > 0 && b.height > 0) ? (double) b.width * b.height : 0.0;
                o.put("area", area);

                o.put("x", b.x);
                o.put("y", b.y);
                o.put("w", b.width);
                o.put("h", b.height);

                arr.put(o);
            } catch (Exception ignore) {
            }
        }

        return arr.toString();
    }

    public static String toCorrectedJson(
            List<ShapeGeometryClassifier.ShapeCandidate> list,
            ColorCalibrator.Model model
    ) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr.toString();

        for (ShapeGeometryClassifier.ShapeCandidate c : list) {
            if (c == null) continue;

            try {
                JSONObject o = new JSONObject();
                Rect b = (c.bbox != null) ? c.bbox : new Rect();

                int rawR = clamp255((int) Math.round(c.meanR));
                int rawG = clamp255((int) Math.round(c.meanG));
                int rawB = clamp255((int) Math.round(c.meanB));

                int corrR = rawR;
                int corrG = rawG;
                int corrB = rawB;

                if (model != null && model.ok) {
                    int[] corr = ColorCalibrator.applyModelToRgb(rawR, rawG, rawB, model);
                    if (corr != null && corr.length >= 3) {
                        corrR = clamp255(corr[0]);
                        corrG = clamp255(corr[1]);
                        corrB = clamp255(corr[2]);
                    }
                }

                o.put("type", c.type != null ? c.type.name() : "UNKNOWN");

                // 원본값도 같이 넣어두면 디버깅이 쉬움
                o.put("r", rawR);
                o.put("g", rawG);
                o.put("b", rawB);

                // ResultActivity가 이 값을 읽어서 Corrected Result에 표시
                o.put("corr_r", corrR);
                o.put("corr_g", corrG);
                o.put("corr_b", corrB);

                double area = (b.width > 0 && b.height > 0) ? (double) b.width * b.height : 0.0;
                o.put("area", area);

                o.put("x", b.x);
                o.put("y", b.y);
                o.put("w", b.width);
                o.put("h", b.height);

                arr.put(o);
            } catch (Exception ignore) {
            }
        }

        return arr.toString();
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}