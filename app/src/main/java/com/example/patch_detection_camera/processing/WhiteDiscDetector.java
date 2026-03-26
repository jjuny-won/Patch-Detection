package com.example.patch_detection_camera.processing;

import android.util.Log;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WhiteDiscDetector {

    private static final String TAG = "WhiteDiscDetector";

    public static class Options {
        public int vMin = 110;
        public int sMax = 150;

        public int morphK = 9;

        public double minAreaRatio = 0.05;  // 5%
        public double maxAreaRatio = 0.95;

        public double minAspect = 0.70;
        public double maxAspect = 1.45;

        // 원형성 필터 (4πA/P^2, 원=1)
        public double minCircularity = 0.55;

        public float padRatio = 0.12f;

        public boolean debug = true;
    }

    public static class Result {
        public Rect roiRect;

        // debug용 (debug=true일 때만 채움)
        public Mat rawMask;
        public Mat morphMask;
        public Mat fillMask;
        public Mat finalMask;

        // 필요하면 contour도 넘겨줌 (사용 후 release)
        public MatOfPoint bestContour;
    }

    public static Result detectDisc(Mat rgba, Options opt) {
        if (opt == null) opt = new Options();
        if (rgba == null || rgba.empty()) return null;

        int W = rgba.cols();
        int H = rgba.rows();
        double imgArea = (double) W * H;
        int total = W * H;

        Mat bgr = new Mat();
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);

        Mat hsv = new Mat();
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);

        // 1) RAW
        Mat raw = new Mat();
        Core.inRange(hsv,
                new Scalar(0, 0, opt.vMin),
                new Scalar(179, opt.sMax, 255),
                raw);

        int nzRaw = Core.countNonZero(raw);
        if (opt.debug) Log.d(TAG, "RAW nz=" + nzRaw + " ratio=" + String.format("%.4f", nzRaw/(double)total));

        // 2) MORPH
        Mat morph = raw.clone();
        int ksz = Math.max(3, opt.morphK);
        if (ksz % 2 == 0) ksz += 1;

        Mat k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(ksz, ksz));
        Imgproc.morphologyEx(morph, morph, Imgproc.MORPH_CLOSE, k);
        Imgproc.morphologyEx(morph, morph, Imgproc.MORPH_OPEN,  k);
        k.release();

        int nzMorph = Core.countNonZero(morph);
        if (opt.debug) Log.d(TAG, "MORPH nz=" + nzMorph + " ratio=" + String.format("%.4f", nzMorph/(double)total));

        // 3) findContours on morph
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(morph, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();

        if (contours.isEmpty()) {
            if (opt.debug) Log.d(TAG, "No contours");
            releaseAll(bgr, hsv, raw, morph);
            return null;
        }

        MatOfPoint best = null;
        double bestScore = -1;
        Point imgCenter = new Point(W/2.0, H/2.0);

        for (MatOfPoint c : contours) {
            double area = Imgproc.contourArea(c);
            double ar = area / imgArea;
            if (ar < opt.minAreaRatio || ar > opt.maxAreaRatio) continue;

            Rect br = Imgproc.boundingRect(c);
            double aspect = (br.width + 1e-6) / (br.height + 1e-6);
            if (aspect < opt.minAspect || aspect > opt.maxAspect) continue;

            MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
            double peri = Imgproc.arcLength(c2f, true);
            c2f.release();
            if (peri < 1e-6) continue;

            double circ = (4.0 * Math.PI * area) / (peri * peri);
            if (circ < opt.minCircularity) continue;

            Moments m = Imgproc.moments(c);
            double cx = (m.m00 != 0) ? (m.m10 / m.m00) : (br.x + br.width/2.0);
            double cy = (m.m00 != 0) ? (m.m01 / m.m00) : (br.y + br.height/2.0);

            double dist = Math.hypot(cx - imgCenter.x, cy - imgCenter.y);
            double distNorm = dist / Math.hypot(imgCenter.x, imgCenter.y);

            // 큰 원판 + 원형성 + 중앙성
            double score = (ar * 2.0) + (circ * 1.5) + (1.0 - distNorm);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best == null) {
            if (opt.debug) Log.d(TAG, "No contour passed filters. (Try relax vMin/sMax/morphK/minArea/minCircularity)");
            for (MatOfPoint c : contours) c.release();
            releaseAll(bgr, hsv, raw, morph);
            return null;
        }

        // 4) FILL mask
        Mat fill = Mat.zeros(rgba.size(), CvType.CV_8UC1);
        Imgproc.drawContours(fill, Collections.singletonList(best), -1, new Scalar(255), -1);

        int nzFill = Core.countNonZero(fill);
        if (opt.debug) Log.d(TAG, "FILL nz=" + nzFill + " ratio=" + String.format("%.4f", nzFill/(double)total)
                + " bestScore=" + String.format("%.3f", bestScore));

        // ROI
        Rect br = Imgproc.boundingRect(best);
        int pad = Math.round(opt.padRatio * Math.min(br.width, br.height));
        Rect roi = expandRect(br, pad, W, H);

        Result out = new Result();
        out.roiRect = roi;
        out.finalMask = fill;
        out.bestContour = best;

        if (opt.debug) {
            out.rawMask = raw;
            out.morphMask = morph;
            out.fillMask = fill.clone();
        } else {
            raw.release();
            morph.release();
        }

        // release non-best contours
        for (MatOfPoint c : contours) {
            if (c != best) c.release();
        }

        bgr.release();
        hsv.release();

        return out;
    }

    private static Rect expandRect(Rect r, int pad, int W, int H) {
        int x = Math.max(0, r.x - pad);
        int y = Math.max(0, r.y - pad);
        int w = Math.min(W - x, r.width + 2 * pad);
        int h = Math.min(H - y, r.height + 2 * pad);
        return new Rect(x, y, w, h);
    }

    private static void releaseAll(Mat... mats) {
        for (Mat m : mats) if (m != null) m.release();
    }
}