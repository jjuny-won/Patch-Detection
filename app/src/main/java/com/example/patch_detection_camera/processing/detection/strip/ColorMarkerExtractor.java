package com.example.patch_detection_camera.processing.detection.strip;

import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;

import java.util.EnumMap;

public class ColorMarkerExtractor {

    private static final String TAG = "ColorMarkerExtractor";

    public static class Result {
        public RotatedRect stripRBox; // strip 위치
        public float[] rgb15;         // [GRAY(RGB), RED, GREEN, BLUE, WHITE] => 15개
        public Mat debugMorph;  // ColorCheckerDetectorEdge.Result.debugMorph
        public Mat debugEdges;  // ColorCheckerDetectorEdge.Result.debugEdges
    }

    /** (기본) 디버그 mats 없이 strip + rgb15만 */
    public static Result extract(Mat rgba, RotatedRect stripRBoxOrNull) {
        return extract(rgba, stripRBoxOrNull, false);
    }

    /** (추가) 디버그 mats까지 필요할 때 */
    public static Result extract(Mat rgba, RotatedRect stripRBoxOrNull, boolean wantDebugMats) {
        Result out = new Result();

        RotatedRect rr = stripRBoxOrNull;

        // fallback: edge detector
        if (rr == null) {
            ColorCheckerDetectorEdge.Options opt = new ColorCheckerDetectorEdge.Options();
            opt.debug = true;
            opt.keepDebugMats = wantDebugMats;

            ColorCheckerDetectorEdge.Result edgeRes =
                    ColorCheckerDetectorEdge.detect(rgba, opt);

            if (edgeRes != null) {
                rr = edgeRes.rect;

                if (wantDebugMats) {
                    out.debugMorph = edgeRes.debugMorph; // ownership: caller releases
                    out.debugEdges = edgeRes.debugEdges; // ownership: caller releases
                } else {
                    // keepDebugMats=false면 detect() 내부에서 이미 release 처리되거나 empty mat 반환할 수 있음
                    // 여기서 따로 release 필요 없음
                }
            }
        }

        out.stripRBox = rr;

        if (rr == null) {
            out.rgb15 = new float[0];
            Log.d(TAG, "strip not found. rgb15 empty.");
            return out;
        }

        ColorStripSampler.StripSample ss =
                ColorStripSampler.sampleFiveBands(rgba, rr, 140, 420);

        if (ss == null || ss.rgbMedian == null) {
            out.rgb15 = new float[0];
            if (ss != null && ss.warped != null) ss.warped.release();
            Log.d(TAG, "sample failed. rgb15 empty.");
            return out;
        }

        out.rgb15 = toRgb15(ss.rgbMedian);

        if (ss.warped != null) ss.warped.release();
        return out;
    }

    private static float[] toRgb15(EnumMap<ColorStripSampler.Band, Scalar> rgbMedian) {
        float[] out = new float[15];
        put(out, 0, rgbMedian.get(ColorStripSampler.Band.GRAY));
        put(out, 1, rgbMedian.get(ColorStripSampler.Band.RED));
        put(out, 2, rgbMedian.get(ColorStripSampler.Band.GREEN));
        put(out, 3, rgbMedian.get(ColorStripSampler.Band.BLUE));
        put(out, 4, rgbMedian.get(ColorStripSampler.Band.WHITE));
        return out;
    }

    private static void put(float[] out, int bandIdx, Scalar rgb) {
        if (rgb == null) return;
        out[bandIdx * 3]     = (float) rgb.val[0]; // R
        out[bandIdx * 3 + 1] = (float) rgb.val[1]; // G
        out[bandIdx * 3 + 2] = (float) rgb.val[2]; // B
    }
}