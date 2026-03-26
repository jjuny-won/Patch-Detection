package com.example.patch_detection_camera.processing.detection.shape;

import android.util.Log;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ShapeDetectorColorFill {

    private static final String TAG = "ShapeFill";

    public static class Options {
        public boolean debug = true;

        public boolean excludeStrip = true;
        public double stripExcludeScale = 1.10;

        // disk area 대비 상대 스케일 컷
        public double minAreaRatio = 0.0055;
        public double maxAreaRatio = 0.14;

        public int minBox = 24;

        // generic colored mask morphology
        public int kCombined = 3;
        public int openIter = 1;
        public int closeIter = 1;

        // geometry classifier options
        public double minCircularity = 0.88;
        public double circleArLo = 0.85;
        public double circleArHi = 1.20;
        public double approxEpsRatio = 0.04;

        // glare reject (low-S, high-V)
        public boolean removeGlare = true;
        public int glareSMax = 45;
        public int glareVMin = 230;

        // valid overlap check
        public boolean requireValidOverlap = true;
        public double minValidOverlapRatio = 0.88;

        // detector 내부 중복 억제
        public boolean enableNms = true;
        public double nmsIouThr = 0.45;

        // debug log
        public boolean logContours = true;

        // generic colored mask threshold
        // 기존보다 조금 덜 공격적으로 하되,
        // 저채도/무채색이 섞이지 않도록 충분히 높게 유지
        public int coloredSMin = 16;
        public int coloredVMin = 25;

        // border reject
        public int borderRejectMargin = 2;

        // legacy 호환용 best-per-type 출력
        public boolean buildLegacyShapes = true;
    }

    public static class Result {
        public List<ShapeGeometryClassifier.ShapeCandidate> candidates = new ArrayList<>();
        public List<ShapeDetector.DetectedShape> shapes = new ArrayList<>();
    }

    public static Result detectFilledShapes(
            Mat rgba,
            Mat diskMask,
            RotatedRect stripRBoxOrNull,
            Options opt
    ) {
        Result result = new Result();

        if (rgba == null || rgba.empty() || diskMask == null || diskMask.empty()) {
            return result;
        }
        if (opt == null) opt = new Options();

        double diskArea = Math.max(1.0, Core.countNonZero(diskMask));
        double minA = diskArea * opt.minAreaRatio;
        double maxA = diskArea * opt.maxAreaRatio;

        // 1) validMask = diskMask - strip
        Mat validMask = diskMask.clone();
        if (opt.excludeStrip && stripRBoxOrNull != null) {
            Mat stripMask = maskFromRotatedRect(rgba.size(), stripRBoxOrNull, opt.stripExcludeScale);
            Mat inv = new Mat();
            Core.bitwise_not(stripMask, inv);
            Core.bitwise_and(validMask, inv, validMask);
            stripMask.release();
            inv.release();
        }

        // 2) RGBA -> BGR -> HSV
        Mat bgr = new Mat();
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
        Imgproc.GaussianBlur(bgr, bgr, new Size(5, 5), 0);

        Mat hsv = new Mat();
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);

        // 3) generic colored mask
        Mat mColored = new Mat();
        Core.inRange(
                hsv,
                new Scalar(0, opt.coloredSMin, opt.coloredVMin),
                new Scalar(179, 255, 255),
                mColored
        );

        // valid mask 적용
        Core.bitwise_and(mColored, validMask, mColored);

        // glare 제거: low saturation + high value
        Mat glareMask = null;
        if (opt.removeGlare) {
            glareMask = new Mat();
            Core.inRange(
                    hsv,
                    new Scalar(0, 0, opt.glareVMin),
                    new Scalar(179, opt.glareSMax, 255),
                    glareMask
            );

            Mat invGlare = new Mat();
            Core.bitwise_not(glareMask, invGlare);
            Core.bitwise_and(mColored, invGlare, mColored);
            invGlare.release();
        }

        morphSolid(mColored, opt.kCombined, opt.openIter, opt.closeIter);

        if (validMask != null && !validMask.empty()) {
            Core.bitwise_and(mColored, validMask, mColored);
        }

        if (opt.debug) {
            Log.d(TAG, String.format(
                    Locale.US,
                    "diskArea=%.1f minA=%.1f maxA=%.1f validNz=%d coloredNz=%d glareNz=%d",
                    diskArea,
                    minA,
                    maxA,
                    Core.countNonZero(validMask),
                    Core.countNonZero(mColored),
                    (glareMask != null ? Core.countNonZero(glareMask) : 0)
            ));
        }

        // 4) contours -> candidate extraction
        result.candidates = extractFromMask(mColored, validMask, minA, maxA, opt, "COLOR");

        if (opt.enableNms) {
            result.candidates = applyNms(result.candidates, opt.nmsIouThr, opt.debug);
        }

        if (opt.buildLegacyShapes) {
            result.shapes = toLegacyShapesBestPerType(result.candidates, opt.debug);
        }

        validMask.release();
        bgr.release();
        hsv.release();
        mColored.release();
        if (glareMask != null) glareMask.release();

        return result;
    }

    private static List<ShapeGeometryClassifier.ShapeCandidate> extractFromMask(
            Mat mask,
            Mat validMask,
            double minA,
            double maxA,
            Options opt,
            String src
    ) {
        List<ShapeGeometryClassifier.ShapeCandidate> out = new ArrayList<>();

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hier = new Mat();
        Imgproc.findContours(mask, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        if (opt.debug) {
            Log.d(TAG, "[" + src + "] contours=" + contours.size());
        }

        for (int i = 0; i < contours.size(); i++) {
            MatOfPoint c = contours.get(i);

            double area = Math.abs(Imgproc.contourArea(c));
            if (area < minA || area > maxA) {
                if (opt.debug && opt.logContours) {
                    Log.d(TAG, String.format(Locale.US,
                            "#%d REJECT AREA area=%.1f minA=%.1f maxA=%.1f",
                            i, area, minA, maxA));
                }
                c.release();
                continue;
            }

            ShapeGeometryClassifier.Options gopt = new ShapeGeometryClassifier.Options();
            gopt.debug = opt.debug;
            gopt.approxEpsRatio = opt.approxEpsRatio;
            gopt.minCircularity = opt.minCircularity;
            gopt.circleArLo = opt.circleArLo;
            gopt.circleArHi = opt.circleArHi;
            gopt.minHintScoreToAccept = 0.75;
            gopt.ambiguityMargin = 0.20;

            ShapeGeometryClassifier.Result gr =
                    ShapeGeometryClassifier.classifyFromContour(c, gopt);

            if (gr == null || gr.features == null) {
                if (opt.debug && opt.logContours) {
                    Log.d(TAG, "#" + i + " REJECT gr=null");
                }
                c.release();
                continue;
            }

            Rect bbox = gr.features.bbox;
            RotatedRect rbox = gr.rbox;
            Point center = gr.center;

            if (bbox.width < opt.minBox || bbox.height < opt.minBox) {
                if (opt.debug && opt.logContours) {
                    Log.d(TAG, "#" + i + " REJECT BOX bbox=" + bbox);
                }
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            if (touchesImageBorder(bbox, mask.cols(), mask.rows(), opt.borderRejectMargin)) {
                if (opt.debug && opt.logContours) {
                    Log.d(TAG, "#" + i + " REJECT BORDER bbox=" + bbox);
                }
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            if (!pointInsideMask(center, validMask)) {
                if (opt.debug && opt.logContours) {
                    Log.d(TAG, "#" + i + " REJECT OUTSIDE center=" + center);
                }
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            double overlapRatio = 1.0;
            if (opt.requireValidOverlap) {
                overlapRatio = contourValidOverlapRatio(c, validMask);
                if (overlapRatio < opt.minValidOverlapRatio) {
                    if (opt.debug && opt.logContours) {
                        Log.d(TAG, String.format(Locale.US,
                                "#%d REJECT OVERLAP overlap=%.3f min=%.3f bbox=%s",
                                i, overlapRatio, opt.minValidOverlapRatio, bbox.toString()));
                    }
                    releaseGeometryResult(gr);
                    c.release();
                    continue;
                }
            }

            ShapeDetector.ShapeType type = gr.type;

            if (opt.debug && opt.logContours) {
                Log.d(TAG, String.format(
                        Locale.US,
                        "#%d bbox=%s area=%.1f v=%d circ=%.3f rRatio=%.3f rect=%.3f => %s geo=%.3f",
                        i,
                        bbox.toString(),
                        gr.features.area,
                        gr.features.vertices,
                        gr.features.circularity,
                        gr.features.rRatio,
                        gr.features.rectangularity,
                        type.name(),
                        gr.score
                ));
            }

            if (type == ShapeDetector.ShapeType.UNKNOWN) {
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            double fillEvidence = computeFillEvidence(
                    gr,
                    overlapRatio,
                    bbox,
                    mask,
                    validMask
            );

            ShapeGeometryClassifier.ShapeCandidate cand =
                    ShapeGeometryClassifier.toCandidate(
                            c,
                            gr,
                            "color_fill",
                            0.0,
                            fillEvidence
                    );

            if (cand == null) {
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            // rescue branch score:
            // fill + geometry 중심, contrast는 거의 사용 안 함
            cand.finalScore = fusedFillRescueScore(
                    cand.contrastScore,
                    cand.fillScore,
                    cand.geometryScore
            );

            if (opt.debug && opt.logContours) {
                Log.d(TAG, String.format(
                        Locale.US,
                        "#%d ACCEPT type=%s src=%s fill=%.3f geo=%.3f final=%.3f bbox=%s area=%.1f",
                        i,
                        cand.type.name(),
                        cand.source,
                        cand.fillScore,
                        cand.geometryScore,
                        cand.finalScore,
                        cand.bbox.toString(),
                        cand.area
                ));
            }

            out.add(cand);
            releaseGeometryResult(gr);
        }

        hier.release();
        return out;
    }

    private static double computeFillEvidence(
            ShapeGeometryClassifier.Result gr,
            double overlapRatio,
            Rect bbox,
            Mat colorMask,
            Mat validMask
    ) {
        if (gr == null || gr.features == null || bbox == null) return 0.0;

        double density = bboxFillDensity(colorMask, bbox);
        double validDensity = bboxFillDensity(validMask, bbox);

        double overlapScore = clamp01(overlapRatio);
        double densityScore = clamp01(density);
        double validDensityScore = clamp01(validDensity);

        double shapeAffinity;
        switch (gr.type) {
            case TRIANGLE:
                shapeAffinity =
                        (0.30 * gr.features.rectangularity) +
                                (0.18 * Math.max(0.0, 1.3 - Math.min(1.3, gr.features.rRatio))) +
                                0.12;
                break;
            case SQUARE:
                shapeAffinity =
                        (0.40 * gr.features.rectangularity) +
                                (0.22 * Math.max(0.0, 1.3 - Math.min(1.3, gr.features.bboxAspectRatio))) +
                                0.12;
                break;
            case HEXAGON:
                shapeAffinity =
                        (0.26 * gr.features.rectangularity) +
                                (0.20 * gr.features.circularity) +
                                (0.16 * Math.max(0.0, 1.3 - Math.min(1.3, gr.features.rRatio))) +
                                0.08;
                break;
            case CIRCLE:
                shapeAffinity =
                        (0.46 * gr.features.circularity) +
                                (0.18 * Math.max(0.0, 1.3 - Math.min(1.3, gr.features.bboxAspectRatio))) +
                                0.08;
                break;
            default:
                shapeAffinity = 0.0;
                break;
        }

        double raw =
                (overlapScore * 1.00) +
                        (densityScore * 0.90) +
                        (validDensityScore * 0.30) +
                        shapeAffinity;

        return clamp01(raw / 2.2);
    }

    private static double bboxFillDensity(Mat mask, Rect bbox) {
        if (mask == null || mask.empty() || bbox == null) return 0.0;

        int x = Math.max(0, bbox.x);
        int y = Math.max(0, bbox.y);
        int w = Math.min(mask.cols() - x, bbox.width);
        int h = Math.min(mask.rows() - y, bbox.height);

        if (w <= 0 || h <= 0) return 0.0;

        Rect roi = new Rect(x, y, w, h);
        Mat sub = new Mat(mask, roi);
        double area = Math.max(1.0, w * h);
        double nz = Core.countNonZero(sub);
        sub.release();
        return nz / area;
    }

    private static void morphSolid(Mat m, int kSize, int openIter, int closeIter) {
        kSize = normalizeKernel(kSize);
        Mat k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(kSize, kSize));

        if (openIter > 0) {
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, k, new Point(-1, -1), openIter);
        }
        if (closeIter > 0) {
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, k, new Point(-1, -1), closeIter);
        }

        k.release();
    }

    private static int normalizeKernel(int kSize) {
        kSize = Math.max(3, kSize);
        if (kSize % 2 == 0) kSize += 1;
        return kSize;
    }

    private static boolean touchesImageBorder(Rect r, int W, int H, int margin) {
        return r.x <= margin ||
                r.y <= margin ||
                (r.x + r.width) >= (W - margin) ||
                (r.y + r.height) >= (H - margin);
    }

    private static Mat maskFromRotatedRect(Size size, RotatedRect rr, double scale) {
        RotatedRect rr2 = new RotatedRect(
                rr.center,
                new Size(rr.size.width * scale, rr.size.height * scale),
                rr.angle
        );
        Point[] pts = new Point[4];
        rr2.points(pts);

        Mat mask = Mat.zeros(size, CvType.CV_8UC1);
        MatOfPoint poly = new MatOfPoint(pts);
        Imgproc.fillConvexPoly(mask, poly, new Scalar(255));
        poly.release();
        return mask;
    }

    private static boolean pointInsideMask(Point p, Mat mask) {
        int x = (int) Math.round(p.x);
        int y = (int) Math.round(p.y);
        if (x < 0 || y < 0 || x >= mask.cols() || y >= mask.rows()) return false;
        double[] v = mask.get(y, x);
        return v != null && v.length > 0 && v[0] > 0;
    }

    private static double contourValidOverlapRatio(MatOfPoint contour, Mat validMask) {
        if (contour == null || contour.empty() || validMask == null || validMask.empty()) {
            return 0.0;
        }

        Mat contourMask = Mat.zeros(validMask.size(), CvType.CV_8UC1);
        List<MatOfPoint> one = new ArrayList<>(1);
        one.add(contour);
        Imgproc.drawContours(contourMask, one, 0, new Scalar(255), Imgproc.FILLED);

        Mat inter = new Mat();
        Core.bitwise_and(contourMask, validMask, inter);

        double contourAreaPx = Math.max(1.0, Core.countNonZero(contourMask));
        double interAreaPx = Core.countNonZero(inter);
        double ratio = interAreaPx / contourAreaPx;

        contourMask.release();
        inter.release();

        return ratio;
    }

    private static List<ShapeGeometryClassifier.ShapeCandidate> applyNms(
            List<ShapeGeometryClassifier.ShapeCandidate> in,
            double iouThr,
            boolean debug
    ) {
        if (in == null || in.size() <= 1) return in;

        List<ShapeGeometryClassifier.ShapeCandidate> sorted = new ArrayList<>(in);
        sorted.sort(new Comparator<ShapeGeometryClassifier.ShapeCandidate>() {
            @Override
            public int compare(ShapeGeometryClassifier.ShapeCandidate a,
                               ShapeGeometryClassifier.ShapeCandidate b) {
                return Double.compare(b.finalScore, a.finalScore);
            }
        });

        List<ShapeGeometryClassifier.ShapeCandidate> keep = new ArrayList<>();
        boolean[] removed = new boolean[sorted.size()];

        for (int i = 0; i < sorted.size(); i++) {
            if (removed[i]) continue;

            ShapeGeometryClassifier.ShapeCandidate a = sorted.get(i);
            keep.add(a);

            for (int j = i + 1; j < sorted.size(); j++) {
                if (removed[j]) continue;

                ShapeGeometryClassifier.ShapeCandidate b = sorted.get(j);

                // 같은 타입끼리만 NMS
                if (a.type != b.type) continue;

                double iou = iou(a.bbox, b.bbox);
                if (iou > iouThr) {
                    removed[j] = true;
                    if (debug) {
                        Log.d(TAG, String.format(Locale.US,
                                "NMS suppress %s iou=%.3f keepFinal=%.3f dropFinal=%.3f srcA=%s srcB=%s",
                                a.type.name(), iou, a.finalScore, b.finalScore, a.source, b.source));
                    }
                }
            }
        }

        return keep;
    }

    private static List<ShapeDetector.DetectedShape> toLegacyShapesBestPerType(
            List<ShapeGeometryClassifier.ShapeCandidate> candidates,
            boolean debug
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<ShapeDetector.DetectedShape> out = new ArrayList<>();

        ShapeGeometryClassifier.ShapeCandidate bestTri = null;
        ShapeGeometryClassifier.ShapeCandidate bestSq = null;
        ShapeGeometryClassifier.ShapeCandidate bestHex = null;
        ShapeGeometryClassifier.ShapeCandidate bestCir = null;

        for (ShapeGeometryClassifier.ShapeCandidate c : candidates) {
            if (c == null || c.type == null) continue;

            switch (c.type) {
                case TRIANGLE:
                    if (bestTri == null || c.finalScore > bestTri.finalScore) bestTri = c;
                    break;
                case SQUARE:
                    if (bestSq == null || c.finalScore > bestSq.finalScore) bestSq = c;
                    break;
                case HEXAGON:
                    if (bestHex == null || c.finalScore > bestHex.finalScore) bestHex = c;
                    break;
                case CIRCLE:
                    if (bestCir == null || c.finalScore > bestCir.finalScore) bestCir = c;
                    break;
                default:
                    break;
            }
        }

        if (bestTri != null) out.add(toDetectedShape(bestTri));
        if (bestSq != null) out.add(toDetectedShape(bestSq));
        if (bestHex != null) out.add(toDetectedShape(bestHex));
        if (bestCir != null) out.add(toDetectedShape(bestCir));

        if (debug) {
            Log.d(TAG, "legacy best-per-type shapes=" + out.size());
            for (ShapeDetector.DetectedShape s : out) {
                Log.d(TAG, String.format(Locale.US,
                        "LEGACY %s bbox=%s area=%.1f score=%.3f",
                        s.type.name(), s.bbox.toString(), s.area, s.score));
            }
        }

        return out;
    }

    private static ShapeDetector.DetectedShape toDetectedShape(
            ShapeGeometryClassifier.ShapeCandidate c
    ) {
        return new ShapeDetector.DetectedShape(
                c.type,
                c.contour,
                c.bbox,
                c.rbox,
                c.center,
                c.area,
                c.finalScore
        );
    }

    private static double iou(Rect a, Rect b) {
        int x1 = Math.max(a.x, b.x);
        int y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width);
        int y2 = Math.min(a.y + a.height, b.y + b.height);

        int iw = Math.max(0, x2 - x1);
        int ih = Math.max(0, y2 - y1);

        double inter = (double) iw * ih;
        double union = (double) a.width * a.height + (double) b.width * b.height - inter;

        if (union <= 1e-6) return 0.0;
        return inter / union;
    }

    private static double fusedFillRescueScore(
            double contrastScore,
            double fillScore,
            double geometryScore
    ) {
        double c = clamp01(contrastScore);
        double f = clamp01(fillScore);
        double g = clamp01(geometryScore / 2.8);
        return (0.10 * c) + (0.55 * f) + (0.35 * g);
    }

    private static void releaseGeometryResult(ShapeGeometryClassifier.Result gr) {
        if (gr == null) return;
        if (gr.hull != null) gr.hull.release();
        if (gr.approx != null) gr.approx.release();
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}