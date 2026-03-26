package com.example.patch_detection_camera.processing.detection.shape;

import android.util.Log;

import org.opencv.core.*;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ShapeDetectorContrast {

    private static final String TAG = "ShapeContrast";

    public static class Options {
        public boolean debug = true;

        public boolean rejectBorderForBlob = true;
        public boolean rejectBorderForRect = false;
        public double maxBorderRectAreaRatio = 0.22;
        public int borderRejectMargin = 3;

        // Preprocess
        public int blurK = 9;
        public boolean useAdaptive = true;
        public int adaptBlock = 51;
        public double adaptC = 5.0;

        // CLAHE
        public boolean useClahe = true;
        public double claheClipLimit = 2.0;
        public int claheTile = 8;

        // glare clamp
        public boolean useHighlightClamp = true;
        public int highlightClampThresh = 205;
        public int highlightClampValue = 145;

        // Morphology (blob path)
        public int morphK = 7;
        public int closeIter = 3;
        public int openIter = 0;

        // Common filtering
        public double minAreaRatio = 0.004;
        public double maxAreaRatioOfValid = 0.40;
        public int minWH = 12;

        // Polygon approx
        public double approxEpsRatio = 0.035;

        // legacy support
        public boolean pickBestPerType = true;

        // Rect rescue
        public boolean enableRectRescue = true;
        public int rectCanny1 = 60;
        public int rectCanny2 = 180;
        public int rectDilateK = 3;
        public int rectDilateIter = 1;
        public double rectMinAreaRatio = 0.002;
        public double rectMaxAreaRatioOfValid = 0.18;
        public int rectMinWH = 24;
        public double rectSquareArMax = 1.30;
        public double rectMinRectangularity = 0.75;
    }

    public static class DebugMats {
        public Mat grayMasked;
        public Mat norm;
        public Mat bin;
        public Mat edges;

        public void releaseAll() {
            if (grayMasked != null) grayMasked.release();
            if (norm != null) norm.release();
            if (bin != null) bin.release();
            if (edges != null) edges.release();
        }
    }

    public static class Result {
        public List<ShapeGeometryClassifier.ShapeCandidate> candidates = new ArrayList<>();
        public List<ShapeDetector.DetectedShape> shapes = new ArrayList<>();
        public DebugMats dbg;
    }

    public static Result detect(Mat rgba, Mat validMask, Options opt) {
        Result out = new Result();
        if (opt == null) opt = new Options();

        DebugMats dbg = opt.debug ? new DebugMats() : null;
        out.dbg = dbg;

        if (rgba == null || rgba.empty()) {
            return out;
        }

        final int W = rgba.cols();
        final int H = rgba.rows();
        final double imgArea = (double) W * H;
        final double validArea = (validMask != null && !validMask.empty())
                ? Core.countNonZero(validMask)
                : imgArea;
        final double minArea = opt.minAreaRatio * imgArea;
        final double maxArea = opt.maxAreaRatioOfValid * Math.max(1.0, validArea);

        if (opt.debug) {
            Log.d(TAG, String.format(Locale.US,
                    "detect() W=%d H=%d imgArea=%.1f validArea=%.1f minArea=%.1f maxArea=%.1f",
                    W, H, imgArea, validArea, minArea, maxArea));
        }

        Mat gray = new Mat();
        Mat grayMasked = new Mat();
        Mat norm = new Mat();
        Mat bin = new Mat();
        Mat edges = new Mat();

        try {
            // 1) gray
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

            // 2) mask
            if (validMask != null && !validMask.empty()) {
                gray.copyTo(grayMasked, validMask);
            } else {
                gray.copyTo(grayMasked);
            }

            gray.copyTo(norm);
            if (validMask != null && !validMask.empty()) {
                Core.bitwise_and(norm, validMask, norm);
            }

            // 3) CLAHE
            if (opt.useClahe) {
                int tile = Math.max(4, opt.claheTile);
                CLAHE clahe = Imgproc.createCLAHE(opt.claheClipLimit, new Size(tile, tile));
                Mat claheOut = new Mat();
                clahe.apply(norm, claheOut);
                norm.release();
                norm = claheOut;
                clahe.collectGarbage();
            }

            // 4) glare clamp
            if (opt.useHighlightClamp) {
                Mat highlightMask = new Mat();
                Imgproc.threshold(norm, highlightMask,
                        opt.highlightClampThresh, 255, Imgproc.THRESH_BINARY);
                norm.setTo(new Scalar(opt.highlightClampValue), highlightMask);
                highlightMask.release();
            }

            // 5) blur
            int bk = Math.max(3, opt.blurK);
            if (bk % 2 == 0) bk += 1;
            Imgproc.GaussianBlur(norm, norm, new Size(bk, bk), 0);

            // 6) threshold
            if (opt.useAdaptive) {
                int block = Math.max(11, opt.adaptBlock);
                if (block % 2 == 0) block += 1;

                Imgproc.adaptiveThreshold(
                        norm, bin, 255,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY_INV,
                        block,
                        opt.adaptC
                );

                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "threshold=ADAPTIVE_INV block=%d C=%.2f binNz(beforeMask)=%d",
                            block, opt.adaptC, Core.countNonZero(bin)));
                }
            } else {
                Imgproc.threshold(norm, bin, 0, 255,
                        Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU);

                if (opt.debug) {
                    Log.d(TAG, "threshold=OTSU_INV binNz(beforeMask)=" + Core.countNonZero(bin));
                }
            }

            if (validMask != null && !validMask.empty()) {
                Core.bitwise_and(bin, validMask, bin);
            }

            if (opt.debug) {
                Log.d(TAG, "binNz(afterMask)=" + Core.countNonZero(bin));
            }

            // 7) morphology
            int mk = Math.max(3, opt.morphK);
            if (mk % 2 == 0) mk += 1;

            Mat morphKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, new Size(mk, mk));

            if (opt.closeIter > 0) {
                Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE,
                        morphKernel, new Point(-1, -1), opt.closeIter);
            }
            if (opt.openIter > 0) {
                Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN,
                        morphKernel, new Point(-1, -1), opt.openIter);
            }

            if (validMask != null && !validMask.empty()) {
                Core.bitwise_and(bin, validMask, bin);
            }

            if (opt.debug) {
                Log.d(TAG, String.format(Locale.US,
                        "morph k=%d closeIter=%d openIter=%d binNz(afterMorph)=%d",
                        mk, opt.closeIter, opt.openIter, Core.countNonZero(bin)));
            }

            // 8) edges
            Imgproc.Canny(norm, edges, opt.rectCanny1, opt.rectCanny2);
            if (validMask != null && !validMask.empty()) {
                Core.bitwise_and(edges, validMask, edges);
            }

            if (opt.debug) {
                Log.d(TAG, "edgesNz=" + Core.countNonZero(edges));
            }

            // 9) collect candidates
            addBlobCandidates(bin, out.candidates, minArea, maxArea, W, H, opt);

            if (opt.enableRectRescue) {
                addRectCandidates(edges, out.candidates, validArea, W, H, opt);
            }

            // 10) optional legacy best-per-type
            if (opt.pickBestPerType) {
                Map<ShapeDetector.ShapeType, ShapeGeometryClassifier.ShapeCandidate> best =
                        new EnumMap<>(ShapeDetector.ShapeType.class);

                for (ShapeGeometryClassifier.ShapeCandidate c : out.candidates) {
                    keepBestCandidate(best, c, "[FINAL]", opt);
                }

                for (ShapeGeometryClassifier.ShapeCandidate c : best.values()) {
                    out.shapes.add(new ShapeDetector.DetectedShape(
                            c.type,
                            c.contour,
                            c.bbox,
                            c.rbox,
                            c.center,
                            c.area,
                            c.finalScore
                    ));
                }
            }

            if (opt.debug) {
                Log.d(TAG, "candidate count=" + out.candidates.size());
                for (ShapeGeometryClassifier.ShapeCandidate c : out.candidates) {
                    if (c == null || c.bbox == null) continue;
                    Log.d(TAG, String.format(Locale.US,
                            "CAND %s src=%s bbox=%s area=%.1f geo=%.3f con=%.3f fill=%.3f final=%.3f",
                            c.type != null ? c.type.name() : "UNKNOWN",
                            c.source,
                            c.bbox.toString(),
                            c.area,
                            c.geometryScore,
                            c.contrastScore,
                            c.fillScore,
                            c.finalScore));
                }

                Log.d(TAG, "legacy shapes=" + out.shapes.size());
                for (ShapeDetector.DetectedShape s : out.shapes) {
                    if (s == null || s.bbox == null) continue;
                    Log.d(TAG, String.format(Locale.US,
                            "FINAL %s bbox=%s area=%.1f score=%.3f",
                            s.type.name(), s.bbox.toString(), s.area, s.score));
                }
            }

            morphKernel.release();

            if (opt.debug) {
                dbg.grayMasked = grayMasked;
                dbg.norm = norm;
                dbg.bin = bin;
                dbg.edges = edges;
            } else {
                grayMasked.release();
                norm.release();
                bin.release();
                edges.release();
            }

            return out;

        } finally {
            gray.release();
        }
    }

    // ---------------------------------------------------------
    // blob path
    // ---------------------------------------------------------

    private static void addBlobCandidates(
            Mat bin,
            List<ShapeGeometryClassifier.ShapeCandidate> outCandidates,
            double minArea, double maxArea,
            int W, int H,
            Options opt
    ) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();

        Imgproc.findContours(bin, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        if (opt.debug) Log.d(TAG, "blob contours=" + contours.size());

        for (int i = 0; i < contours.size(); i++) {
            MatOfPoint c = contours.get(i);
            double area = Math.abs(Imgproc.contourArea(c));
            Rect rawBox = Imgproc.boundingRect(c);

            if (opt.debug) {
                Log.d(TAG, String.format(Locale.US,
                        "[BLOB] #%d raw bbox=%s area=%.1f",
                        i, rawBox.toString(), area));
            }

            if (area < minArea || area > maxArea) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "[BLOB] #%d REJECT AREA area=%.1f minArea=%.1f maxArea=%.1f",
                            i, area, minArea, maxArea));
                }
                c.release();
                continue;
            }

            if (rawBox.width < opt.minWH || rawBox.height < opt.minWH) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "[BLOB] #%d REJECT RAW BOX bbox=%s minWH=%d",
                            i, rawBox.toString(), opt.minWH));
                }
                c.release();
                continue;
            }

            ShapeGeometryClassifier.Result gr =
                    ShapeGeometryClassifier.classifyFromContour(c, buildGeometryOptions(opt));

            if (gr == null || gr.features == null) {
                if (opt.debug) Log.d(TAG, "[BLOB] #" + i + " REJECT gr=null");
                c.release();
                continue;
            }

            Rect bbox = gr.features.bbox;

            if (opt.rejectBorderForBlob &&
                    touchesImageBorder(bbox, W, H, opt.borderRejectMargin)) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "[BLOB] #%d REJECT BORDER bbox=%s margin=%d",
                            i, bbox.toString(), opt.borderRejectMargin));
                }
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            if (bbox.width < opt.minWH || bbox.height < opt.minWH) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "[BLOB] #%d REJECT CLASSIFIER BOX bbox=%s minWH=%d",
                            i, bbox.toString(), opt.minWH));
                }
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            ShapeDetector.ShapeType type = gr.type;
            if (type == ShapeDetector.ShapeType.UNKNOWN) {
                if (opt.debug) Log.d(TAG, "[BLOB] #" + i + " REJECT UNKNOWN");
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            double contrastEvidence = computeBlobContrastEvidence(gr, bbox, W, H, opt);

            if (opt.debug) {
                Log.d(TAG, String.format(Locale.US,
                        "[BLOB] #%d classified bbox=%s area=%.1f v=%d circ=%.3f rRatio=%.3f rect=%.3f => %s geo=%.3f con=%.3f",
                        i, bbox.toString(), gr.features.area, gr.features.vertices,
                        gr.features.circularity, gr.features.rRatio, gr.features.rectangularity,
                        gr.type.name(), gr.score, contrastEvidence));
            }

            ShapeGeometryClassifier.ShapeCandidate cand =
                    ShapeGeometryClassifier.toCandidate(
                            c,
                            gr,
                            "contrast_blob",
                            contrastEvidence,
                            0.0
                    );

            if (cand == null || cand.type == ShapeDetector.ShapeType.UNKNOWN) {
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            cand.finalScore = fusedContrastMainScore(
                    cand.contrastScore,
                    cand.fillScore,
                    cand.geometryScore
            );

            outCandidates.add(cand);
            releaseGeometryResult(gr);
        }

        hierarchy.release();
    }

    // ---------------------------------------------------------
    // rect rescue
    // ---------------------------------------------------------

    private static void addRectCandidates(
            Mat edges,
            List<ShapeGeometryClassifier.ShapeCandidate> outCandidates,
            double validArea,
            int W, int H,
            Options opt
    ) {
        Mat work = edges.clone();

        int dk = Math.max(3, opt.rectDilateK);
        if (dk % 2 == 0) dk += 1;

        Mat rectKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, new Size(dk, dk));

        if (opt.rectDilateIter > 0) {
            Imgproc.dilate(work, work, rectKernel,
                    new Point(-1, -1), opt.rectDilateIter);
        }

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(work, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        double rectMinArea = opt.rectMinAreaRatio * W * H;
        double rectMaxArea = opt.rectMaxAreaRatioOfValid * Math.max(1.0, validArea);

        if (opt.debug) {
            Log.d(TAG, String.format(Locale.US,
                    "rect contours=%d rectMinArea=%.1f rectMaxArea=%.1f",
                    contours.size(), rectMinArea, rectMaxArea));
        }

        for (int i = 0; i < contours.size(); i++) {
            MatOfPoint c = contours.get(i);
            double area = Math.abs(Imgproc.contourArea(c));
            Rect bbox = Imgproc.boundingRect(c);

            if (opt.debug) {
                Log.d(TAG, String.format(Locale.US,
                        "[RECT] #%d raw bbox=%s area=%.1f", i, bbox.toString(), area));
            }

            if (area < rectMinArea || area > rectMaxArea) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "[RECT] #%d REJECT AREA area=%.1f min=%.1f max=%.1f",
                            i, area, rectMinArea, rectMaxArea));
                }
                c.release();
                continue;
            }

            if (bbox.width < opt.rectMinWH || bbox.height < opt.rectMinWH) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "[RECT] #%d REJECT MIN RECT BOX bbox=%s rectMinWH=%d",
                            i, bbox.toString(), opt.rectMinWH));
                }
                c.release();
                continue;
            }

            boolean touchesBorder = touchesImageBorder(bbox, W, H, opt.borderRejectMargin);
            double boxArea = bbox.width * (double) bbox.height;
            double imgArea = (double) W * H;
            boolean tooLargeBorderRect = boxArea > (opt.maxBorderRectAreaRatio * imgArea);

            if (opt.rejectBorderForRect && touchesBorder) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "[RECT] #%d REJECT BORDER bbox=%s margin=%d",
                            i, bbox.toString(), opt.borderRejectMargin));
                }
                c.release();
                continue;
            }

            if (touchesBorder && tooLargeBorderRect) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "[RECT] #%d REJECT LARGE BORDER RECT bbox=%s boxArea=%.1f",
                            i, bbox.toString(), boxArea));
                }
                c.release();
                continue;
            }


            ShapeGeometryClassifier.Result gr =
                    ShapeGeometryClassifier.classifyFromContour(c, buildGeometryOptions(opt));

            if (gr == null || gr.features == null) {
                if (opt.debug) Log.d(TAG, "[RECT] #" + i + " REJECT gr=null");
                c.release();
                continue;
            }

            Rect cb = gr.features.bbox;
            double ar = cb.width / (double) Math.max(1, cb.height);
            ar = (ar < 1.0) ? (1.0 / ar) : ar;

            boolean rectLike =
                    ar <= opt.rectSquareArMax &&
                            gr.features.rectangularity >= opt.rectMinRectangularity &&
                            gr.features.rRatio < 1.25;

            boolean triangleLike =
                    gr.triangleScore >= 1.05 &&          // 기존 1.2 -> 완화
                            gr.features.rectangularity >= 0.45 && // 기존 0.50 -> 완화
                            gr.features.rectangularity < 0.78 &&
                            gr.features.circularity < 0.82 &&
                            ar <= 1.90;

            ShapeDetector.ShapeType forcedType = gr.type;

            if (triangleLike && !rectLike) {
                forcedType = ShapeDetector.ShapeType.TRIANGLE;
            } else if (rectLike) {
                // 원/육각형이더라도 rect rescue 경로에서는 square만 살릴지 여부 선택
                if (gr.type == ShapeDetector.ShapeType.SQUARE) {
                    forcedType = ShapeDetector.ShapeType.SQUARE;
                }
            }

            if (!rectLike && !triangleLike &&
                    forcedType != ShapeDetector.ShapeType.CIRCLE &&
                    forcedType != ShapeDetector.ShapeType.HEXAGON) {
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            if (opt.debug) {
                Log.d(TAG, String.format(Locale.US,
                        "[RECT] #%d classified bbox=%s area=%.1f v=%d circ=%.3f rRatio=%.3f rect=%.3f ar=%.3f rectLike=%s triangleLike=%s type=%s forced=%s geo=%.3f triScore=%.3f",
                        i, cb.toString(), gr.features.area, gr.features.vertices,
                        gr.features.circularity, gr.features.rRatio, gr.features.rectangularity,
                        ar, rectLike ? "Y" : "N", triangleLike ? "Y" : "N",
                        gr.type.name(), forcedType.name(), gr.score, gr.triangleScore));
            }

            double contrastEvidence = computeRectContrastEvidence(gr, ar, touchesBorder);

            ShapeGeometryClassifier.ShapeCandidate cand =
                    ShapeGeometryClassifier.toCandidate(
                            c,
                            gr,
                            "contrast_rect",
                            contrastEvidence,
                            0.0
                    );

            if (cand == null) {
                releaseGeometryResult(gr);
                c.release();
                continue;
            }

            cand.type = forcedType;


            if (cand.type == ShapeDetector.ShapeType.TRIANGLE) {
                cand.geometryScore = Math.max(
                        cand.geometryScore,
                        (gr.triangleScore * 1.15)
                                + (gr.features.rectangularity * 0.55)
                                + (Math.max(0.0, 1.8 - ar) * 0.35)
                                + ((gr.features.vertices == 3) ? 0.35 : 0.0)
                );
            } else if (cand.type == ShapeDetector.ShapeType.SQUARE) {
                cand.geometryScore = Math.max(
                        cand.geometryScore,
                        (gr.features.rectangularity * 1.7)
                                + (Math.max(0.0, 1.5 - ar) * 1.2)
                                + ((gr.features.vertices == 4) ? 0.4 : 0.0)
                );
            }

            cand.finalScore = fusedContrastMainScore(
                    cand.contrastScore,
                    cand.fillScore,
                    cand.geometryScore
            );

            outCandidates.add(cand);
            releaseGeometryResult(gr);
        }

        hierarchy.release();
        rectKernel.release();
        work.release();
    }

    // ---------------------------------------------------------
    // helpers
    // ---------------------------------------------------------

    private static ShapeGeometryClassifier.Options buildGeometryOptions(Options opt) {
        ShapeGeometryClassifier.Options gopt = new ShapeGeometryClassifier.Options();
        gopt.debug = opt.debug;
        gopt.approxEpsRatio = opt.approxEpsRatio;

        gopt.minCircularity = 0.90;
        gopt.circleArLo = 0.80;
        gopt.circleArHi = 1.25;
        gopt.circleLikeHexCirc = 0.94;
        gopt.circleLikeHexRRatio = 1.15;
        gopt.circleLike7Circ = 0.93;
        gopt.circleLike7RRatio = 1.20;

        gopt.minHintScoreToAccept = 0.75;
        gopt.ambiguityMargin = 0.20;
        return gopt;
    }

    private static double computeBlobContrastEvidence(
            ShapeGeometryClassifier.Result gr,
            Rect bbox,
            int W,
            int H,
            Options opt
    ) {
        if (gr == null || gr.features == null) return 0.0;

        double borderBonus = touchesImageBorder(bbox, W, H, opt.borderRejectMargin) ? 0.0 : 0.20;
        double compactness = Math.max(0.0, 1.35 - Math.min(1.35, gr.features.rRatio));
        double rectness = Math.max(0.0, gr.features.rectangularity);
        double circness = Math.max(0.0, gr.features.circularity);

        double typeAffinity;
        switch (gr.type) {
            case TRIANGLE:
                typeAffinity = (0.55 * rectness) + (0.25 * compactness) + 0.20;
                break;
            case SQUARE:
                typeAffinity = (0.50 * rectness) + (0.30 * compactness) + 0.15;
                break;
            case HEXAGON:
                typeAffinity = (0.40 * rectness) + (0.35 * compactness) + (0.15 * circness) + 0.10;
                break;
            case CIRCLE:
                typeAffinity = (0.65 * circness) + (0.20 * compactness) + 0.10;
                break;
            default:
                typeAffinity = (0.40 * rectness) + (0.20 * compactness);
                break;
        }

        return clamp01((borderBonus + typeAffinity) / 1.6);
    }

    private static double computeRectContrastEvidence(
            ShapeGeometryClassifier.Result gr,
            double ar,
            boolean touchesBorder
    ) {
        if (gr == null || gr.features == null) return 0.0;

        double borderPenalty = touchesBorder ? 0.15 : 0.0;
        double arScore = Math.max(0.0, 1.5 - Math.min(1.5, ar));
        double rectScore = Math.max(0.0, gr.features.rectangularity);
        double ratioScore = Math.max(0.0, 1.3 - Math.min(1.3, gr.features.rRatio));
        double vBonus = (gr.features.vertices == 4) ? 0.25 : 0.0;

        double raw =
                (rectScore * 1.1) +
                        (arScore * 0.8) +
                        (ratioScore * 0.5) +
                        vBonus -
                        borderPenalty;

        return clamp01(raw / 2.3);
    }

    private static double fusedContrastMainScore(
            double contrastScore,
            double fillScore,
            double geometryScore
    ) {
        double c = clamp01(contrastScore);
        double f = clamp01(fillScore);
        double g = clamp01(geometryScore / 2.8);
        return (0.50 * c) + (0.15 * f) + (0.35 * g);
    }

    private static void keepBestCandidate(
            Map<ShapeDetector.ShapeType, ShapeGeometryClassifier.ShapeCandidate> best,
            ShapeGeometryClassifier.ShapeCandidate cand,
            String tag,
            Options opt
    ) {
        if (cand == null || cand.type == null) return;

        ShapeGeometryClassifier.ShapeCandidate cur = best.get(cand.type);
        if (cur == null || cand.finalScore > cur.finalScore) {
            if (opt.debug) {
                Log.d(TAG, String.format(Locale.US,
                        "%s KEEP BEST type=%s score=%.3f",
                        tag, cand.type.name(), cand.finalScore));
            }
            best.put(cand.type, cand);
        } else {
            if (opt.debug) {
                Log.d(TAG, String.format(Locale.US,
                        "%s REJECT LOWER SCORE type=%s score=%.3f cur=%.3f",
                        tag, cand.type.name(), cand.finalScore, cur.finalScore));
            }
        }
    }

    private static boolean touchesImageBorder(Rect r, int W, int H, int margin) {
        return r.x <= margin
                || r.y <= margin
                || (r.x + r.width) >= (W - margin)
                || (r.y + r.height) >= (H - margin);
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