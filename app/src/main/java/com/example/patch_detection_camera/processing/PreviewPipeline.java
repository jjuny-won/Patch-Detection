package com.example.patch_detection_camera.processing;

import android.util.Log;

import com.example.patch_detection_camera.processing.detection.shape.ShapeDetector;
import com.example.patch_detection_camera.processing.detection.shape.ShapeDetectorColorFill;
import com.example.patch_detection_camera.processing.detection.shape.ShapeDetectorContrast;
import com.example.patch_detection_camera.processing.detection.shape.ShapeGeometryClassifier;
import com.example.patch_detection_camera.processing.detection.strip.ColorMarkerExtractor;
import com.example.patch_detection_camera.processing.detection.strip.ColorStripSampler;
import com.example.patch_detection_camera.processing.detection.strip.StripInnerBlockDetector;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PreviewPipeline {

    private static final String TAG = "PreviewPipeline";

    public enum DebugView {
        OVERLAY,
        VALID,
        SHAPE_MASK,
        STRIP_CANDIDATE,
        STRIP_EDGES,
        STRIP_WARP,
        STRIP_MASK
    }

    public static class Options {
        public boolean enableWhiteDisc = true;
        public boolean debugStrip = true;
        public double stripScale = 1.20;
    }

    public static class DebugSnapshot {
        public RotatedRect stripRBox;
        public int rgbLen = -1;
        public int candNz = -1;
        public int edgesNz = -1;
        public int bandIdx = 0;
    }

    public static class Out implements AutoCloseable {
        public Mat displayRgba;
        public String shapesJson = "[]";
        public float[] rgb15 = new float[0];
        public DebugSnapshot snap = new DebugSnapshot();

        private final List<Mat> owned = new ArrayList<>();
        public List<ShapeGeometryClassifier.ShapeCandidate> shapeCandidates;
        private void own(Mat m) {
            if (m != null) owned.add(m);
        }

        @Override
        public void close() {
            for (Mat m : owned) {
                try {
                    if (m != null) m.release();
                } catch (Exception ignore) {}
            }

            if (displayRgba != null) {
                displayRgba.release();
                displayRgba = null;
            }

            owned.clear();
        }
    }

    public static Out run(Mat rgbaIn, DebugView view, int bandIdx, Options opt) {
        Out out = new Out();
        if (opt == null) opt = new Options();

        Mat rgba = rgbaIn;
        if (rgba == null || rgba.empty()) {
            out.displayRgba = new Mat();
            out.shapesJson = "[]";
            return out;
        }

        Mat diskMask = null;
        Mat validMask = null;

        Mat stripCand = null;
        Mat stripEdges = null;
        RotatedRect strip = null;

        ColorStripSampler.StripSample ss = null;

        ShapeDetectorColorFill.Result fillResult = null;
        ShapeDetectorContrast.Result contrastResult = null;

        List<ShapeGeometryClassifier.ShapeCandidate> fillCandidates = new ArrayList<>();
        List<ShapeGeometryClassifier.ShapeCandidate> contrastCandidates = new ArrayList<>();
        List<ShapeGeometryClassifier.ShapeCandidate> finalCandidates = new ArrayList<>();

        try {
            // ------------------------------------------------------------
            // 1) diskMask
            // ------------------------------------------------------------
            if (opt.enableWhiteDisc) {
                WhiteDiscDetector.Options dop = new WhiteDiscDetector.Options();
                dop.debug = false;
                WhiteDiscDetector.Result dr = WhiteDiscDetector.detectDisc(rgba, dop);
                if (dr != null && dr.finalMask != null && !dr.finalMask.empty()) {
                    diskMask = dr.finalMask.clone();
                    out.own(diskMask);
                }
            }

            if (diskMask == null || diskMask.empty()) {
                diskMask = Mat.ones(rgba.size(), CvType.CV_8UC1);
                Core.multiply(diskMask, new Scalar(255), diskMask);
                out.own(diskMask);
            }

            // ------------------------------------------------------------
            // 2) strip
            // ------------------------------------------------------------
            ColorMarkerExtractor.Result mr = ColorMarkerExtractor.extract(rgba, null, opt.debugStrip);
            strip = (mr != null) ? mr.stripRBox : null;
            out.rgb15 = (mr != null && mr.rgb15 != null) ? mr.rgb15 : new float[0];

            if (mr != null) {
                if (mr.debugMorph != null && !mr.debugMorph.empty()) {
                    stripCand = mr.debugMorph.clone();
                    out.own(stripCand);
                }
                if (mr.debugEdges != null && !mr.debugEdges.empty()) {
                    stripEdges = mr.debugEdges.clone();
                    out.own(stripEdges);
                }
            }

            out.snap.stripRBox = strip;
            out.snap.rgbLen = out.rgb15 != null ? out.rgb15.length : -1;
            out.snap.candNz = (stripCand != null && !stripCand.empty()) ? Core.countNonZero(stripCand) : -1;
            out.snap.edgesNz = (stripEdges != null && !stripEdges.empty()) ? Core.countNonZero(stripEdges) : -1;
            out.snap.bandIdx = bandIdx;

            if (view == DebugView.STRIP_CANDIDATE) {
                out.displayRgba = (stripCand != null && !stripCand.empty())
                        ? grayMaskToRgba(stripCand)
                        : rgba.clone();
                out.own(out.displayRgba);
                out.shapesJson = "[]";
                return out;
            }

            if (view == DebugView.STRIP_EDGES) {
                out.displayRgba = (stripEdges != null && !stripEdges.empty())
                        ? grayMaskToRgba(stripEdges)
                        : rgba.clone();
                out.own(out.displayRgba);
                out.shapesJson = "[]";
                return out;
            }

            // ------------------------------------------------------------
            // 3) validMask = innerDisk - strip
            // ------------------------------------------------------------
            Mat innerDisk = erodeMask(diskMask, 9, 1);
            out.own(innerDisk);

            validMask = innerDisk.clone();
            out.own(validMask);

            if (strip != null) {
                Mat stripMask = maskFromRotatedRect(rgba.size(), strip, opt.stripScale);
                Mat inv = new Mat();
                out.own(stripMask);
                out.own(inv);

                Core.bitwise_not(stripMask, inv);
                Core.bitwise_and(validMask, inv, validMask);
            }

            if (view == DebugView.VALID) {
                out.displayRgba = grayMaskToRgba(validMask);
                out.own(out.displayRgba);
                out.shapesJson = "[]";
                return out;
            }

            // ------------------------------------------------------------
            // 4) STRIP_WARP / STRIP_MASK
            // ------------------------------------------------------------
            if (view == DebugView.STRIP_WARP || view == DebugView.STRIP_MASK) {
                if (strip == null) {
                    out.displayRgba = rgba.clone();
                    out.own(out.displayRgba);
                    out.shapesJson = "[]";
                    return out;
                }

                ss = ColorStripSampler.sampleFiveBands(rgba, strip, 140, 420);
                if (ss == null || ss.warped == null || ss.warped.empty()) {
                    out.displayRgba = rgba.clone();
                    out.own(out.displayRgba);
                    out.shapesJson = "[]";
                    return out;
                }

                Mat warped = ss.warped;
                Mat warpedOwned = warped.clone();
                out.own(warpedOwned);

                StripInnerBlockDetector.Options bop = new StripInnerBlockDetector.Options();
                StripInnerBlockDetector.Result rr = StripInnerBlockDetector.detectBands(warpedOwned, bop);

                if (rr == null || rr.bands == null || rr.bands.size() < 5) {
                    out.displayRgba = warpedOwned;
                    out.shapesJson = "[]";
                    return out;
                }

                if (view == DebugView.STRIP_WARP) {
                    for (int i = 0; i < rr.bands.size(); i++) {
                        StripInnerBlockDetector.BandResult br = rr.bands.get(i);
                        if (br == null) continue;

                        Rect bandR = clampRect(br.bandRect, warpedOwned.cols(), warpedOwned.rows());
                        Rect innerR = clampRect(br.innerRect, warpedOwned.cols(), warpedOwned.rows());

                        Imgproc.rectangle(warpedOwned, bandR, new Scalar(0, 255, 0, 255), 2);
                        Imgproc.rectangle(warpedOwned, innerR, new Scalar(255, 255, 0, 255), 2);
                        Imgproc.putText(warpedOwned, "B" + i,
                                new Point(Math.max(3, bandR.x + 6), Math.max(18, bandR.y + 22)),
                                Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(0, 255, 0, 255), 2);
                    }
                    out.displayRgba = warpedOwned;
                } else {
                    int idx = Math.max(0, Math.min(4, bandIdx));
                    StripInnerBlockDetector.BandResult br = rr.bands.get(idx);

                    Mat mask = Mat.zeros(warpedOwned.size(), CvType.CV_8UC1);
                    out.own(mask);

                    if (br != null) {
                        Rect innerR = clampRect(br.innerRect, warpedOwned.cols(), warpedOwned.rows());
                        Imgproc.rectangle(mask, innerR, new Scalar(255), -1);
                    }

                    out.displayRgba = grayMaskToRgba(mask);
                    out.own(out.displayRgba);
                }
                out.shapesJson = "[]";
                return out;
            }

            // ------------------------------------------------------------
            // 5) Color fill detector (support / rescue only)
            // ------------------------------------------------------------
            ShapeDetectorColorFill.Options fopt = new ShapeDetectorColorFill.Options();
            fopt.debug = true;
            fopt.excludeStrip = true;
            fopt.stripExcludeScale = 1.10;
            fopt.maxAreaRatio = 0.20;
            fopt.kCombined = 5;
            fopt.borderRejectMargin = 0;
            fopt.coloredSMin = 60;
            fopt.coloredVMin = 50;
            fillResult = ShapeDetectorColorFill.detectFilledShapes(rgba, innerDisk, strip, fopt);
            if (fillResult != null && fillResult.candidates != null) {
                fillCandidates.addAll(fillResult.candidates);
            }

            for (ShapeGeometryClassifier.ShapeCandidate c : fillCandidates) {
                if (c == null) continue;
                c.source = "fill_rescue";
                c.fillScore = clamp01(c.fillScore);
                c.contrastScore = 0.0;
                c.finalScore = fusedScore(c.contrastScore, c.fillScore, c.geometryScore, false);
            }

            // ------------------------------------------------------------
            // 6) Contrast detector (main proposal branch)
            // ------------------------------------------------------------
            ShapeDetectorContrast.Options cop = new ShapeDetectorContrast.Options();
            cop.debug = true;

            cop.blurK = 7;
            cop.adaptBlock = 41;
            cop.adaptC = 4.0;
            cop.morphK = 7;
            cop.closeIter = 2;
            cop.openIter = 0;
            cop.minAreaRatio = 0.003;
            cop.borderRejectMargin = 3;

            contrastResult = ShapeDetectorContrast.detect(rgba, validMask, cop);

            contrastCandidates = buildContrastCandidates(
                    contrastResult != null ? contrastResult.shapes : null
            );

            applyFillEvidence(contrastCandidates, fillCandidates);

            List<ShapeGeometryClassifier.ShapeCandidate> selectedContrast =
                    selectContrastMain(contrastCandidates);

            List<ShapeGeometryClassifier.ShapeCandidate> rescuedFill =
                    selectFillRescue(fillCandidates, selectedContrast);

            finalCandidates.addAll(selectedContrast);
            finalCandidates.addAll(rescuedFill);

            finalCandidates = dedupeFinal(finalCandidates);
            finalCandidates = applyQuadrantConstraint(finalCandidates, diskMask, strip);
            finalCandidates = rescueMissingQuadrants(rgba, finalCandidates, diskMask, strip, validMask);
            sampleCandidateColors(rgba, finalCandidates);

            out.shapesJson = ShapeJsonUtils.toJson(finalCandidates);

            if (fopt.debug) {
                for (ShapeGeometryClassifier.ShapeCandidate c : contrastCandidates) {
                    if (c == null || c.bbox == null) continue;
                    Log.d(TAG, String.format(
                            Locale.US,
                            "ContrastCand type=%s src=%s bbox=%s contrast=%.3f fill=%.3f geo=%.3f final=%.3f",
                            c.type != null ? c.type.name() : "null",
                            c.source,
                            c.bbox.toString(),
                            c.contrastScore,
                            c.fillScore,
                            c.geometryScore,
                            c.finalScore
                    ));
                }

                for (ShapeGeometryClassifier.ShapeCandidate c : rescuedFill) {
                    if (c == null || c.bbox == null) continue;
                    Log.d(TAG, String.format(
                            Locale.US,
                            "RescueFill type=%s src=%s bbox=%s contrast=%.3f fill=%.3f geo=%.3f final=%.3f",
                            c.type != null ? c.type.name() : "null",
                            c.source,
                            c.bbox.toString(),
                            c.contrastScore,
                            c.fillScore,
                            c.geometryScore,
                            c.finalScore
                    ));
                }
            }

            // ------------------------------------------------------------
            // 7) SHAPE_MASK
            // ------------------------------------------------------------
            if (view == DebugView.SHAPE_MASK) {
                Mat shapeMask = Mat.zeros(rgba.size(), CvType.CV_8UC1);
                out.own(shapeMask);

                for (ShapeGeometryClassifier.ShapeCandidate s : finalCandidates) {
                    if (s == null) continue;

                    if (s.contour != null && !s.contour.empty()) {
                        List<MatOfPoint> one = new ArrayList<>();
                        one.add(s.contour);
                        Imgproc.fillPoly(shapeMask, one, new Scalar(255));
                    } else if (s.bbox != null) {
                        Rect r = clampRect(s.bbox, rgba.cols(), rgba.rows());
                        Imgproc.rectangle(shapeMask, r, new Scalar(255), -1);
                    }
                }

                out.displayRgba = grayMaskToRgba(shapeMask);
                out.own(out.displayRgba);

                releaseCandidateContours(finalCandidates);
                releaseContrastContours(contrastResult);
                releaseFillContours(fillCandidates);
                return out;
            }

            // ------------------------------------------------------------
            // 8) OVERLAY
            // ------------------------------------------------------------
            Mat disp = rgba.clone();
            out.own(disp);

            if (strip != null) {
                drawRotatedRect(disp, strip, new Scalar(255, 0, 0, 255), 3);
                Imgproc.putText(disp, "COLOR_CHECKER",
                        new Point(strip.center.x - 60, Math.max(0, (int) strip.center.y - 10)),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(255, 0, 0, 255), 2);
            }

            for (ShapeGeometryClassifier.ShapeCandidate s : finalCandidates) {
                if (s == null || s.bbox == null) continue;

                boolean inside = isInsideMask(diskMask, s.bbox);
                Scalar color = inside ? new Scalar(0, 255, 0, 255) : new Scalar(0, 0, 255, 255);

                if (inside && s.type == ShapeDetector.ShapeType.CIRCLE) {
                    color = new Scalar(255, 255, 0, 255);
                } else if (inside && "fill_rescue".equals(s.source)) {
                    color = new Scalar(255, 0, 255, 255);
                }

                drawShapeOutline(disp, s, color, 2);

                // Draw the color-sampling region (bbox center 20%) as a white dashed rectangle
                int scx = s.bbox.x + s.bbox.width  / 2;
                int scy = s.bbox.y + s.bbox.height / 2;
                int sw  = Math.max(1, s.bbox.width  / 5);
                int sh  = Math.max(1, s.bbox.height / 5);
                Rect samplingRect = clampRect(
                        new Rect(scx - sw / 2, scy - sh / 2, sw, sh),
                        disp.cols(), disp.rows());
                Imgproc.rectangle(disp, samplingRect, new Scalar(255, 255, 255, 255), 2);

                String label = (s.type != null ? s.type.name() : "UNKNOWN")
                        + String.format(Locale.US, " %.2f", s.finalScore);

                Imgproc.putText(disp, label,
                        new Point(s.bbox.x, Math.max(0, s.bbox.y - 6)),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, color, 2);
            }

            out.displayRgba = disp;
            out.shapeCandidates = finalCandidates;

            releaseCandidateContours(finalCandidates);
            releaseContrastContours(contrastResult);
            releaseFillContours(fillCandidates);
            return out;

        } catch (Exception e) {
            Log.e(TAG, "run error", e);
            out.displayRgba = rgba.clone();
            out.own(out.displayRgba);
            out.shapesJson = "[]";

            releaseCandidateContours(finalCandidates);
            releaseContrastContours(contrastResult);
            releaseFillContours(fillCandidates);




            return out;
        } finally {
            if (ss != null && ss.warped != null) ss.warped.release();
        }
    }

    // ---------------------------------------------------------------------
    // Contrast-first candidate building
    // ---------------------------------------------------------------------

    private static List<ShapeGeometryClassifier.ShapeCandidate> buildContrastCandidates(
            List<ShapeDetector.DetectedShape> shapes
    ) {
        List<ShapeGeometryClassifier.ShapeCandidate> out = new ArrayList<>();
        if (shapes == null) return out;

        ShapeGeometryClassifier.Options gopt = new ShapeGeometryClassifier.Options();
        gopt.debug = true;
        gopt.minHintScoreToAccept = 0.75;
        gopt.ambiguityMargin = 0.20;

        for (ShapeDetector.DetectedShape s : shapes) {
            if (s == null || s.bbox == null) continue;

            MatOfPoint contour = s.contour;
            if (contour == null || contour.empty()) {
                contour = rectContour(s.bbox);
            }

            if (contour == null || contour.empty()) continue;

            ShapeGeometryClassifier.Result gr =
                    ShapeGeometryClassifier.classifyFromContour(contour, gopt);

            if (gr == null || gr.features == null) {
                if (contour != s.contour) contour.release();
                continue;
            }

            ShapeGeometryClassifier.ShapeCandidate c =
                    ShapeGeometryClassifier.toCandidate(contour, gr, "contrast_main", 1.0, 0.0);

            if (c == null) {
                if (contour != s.contour) contour.release();
                continue;
            }

            c.contrastScore = 1.0;
            c.fillScore = 0.0;
            c.finalScore = fusedScore(c.contrastScore, c.fillScore, c.geometryScore, true);

            out.add(c);

            if (gr.hull != null) gr.hull.release();
            if (gr.approx != null) gr.approx.release();
        }

        return out;
    }

    private static void applyFillEvidence(
            List<ShapeGeometryClassifier.ShapeCandidate> contrastCandidates,
            List<ShapeGeometryClassifier.ShapeCandidate> fillCandidates
    ) {
        if (contrastCandidates == null || contrastCandidates.isEmpty()) return;

        for (ShapeGeometryClassifier.ShapeCandidate base : contrastCandidates) {
            if (base == null || base.bbox == null) continue;

            double bestFillEvidence = 0.0;

            if (fillCandidates != null) {
                for (ShapeGeometryClassifier.ShapeCandidate fc : fillCandidates) {
                    if (fc == null || fc.bbox == null) continue;

                    double iou = iou(base.bbox, fc.bbox);
                    double centerScore = bboxContains(fc.bbox, base.center) ? 1.0 : 0.0;
                    double typeScore = sameKnownType(base.type, fc.type) ? 1.0 : 0.0;

                    double evidence =
                            (0.65 * iou) +
                                    (0.25 * centerScore) +
                                    (0.10 * typeScore);

                    if (evidence > bestFillEvidence) {
                        bestFillEvidence = evidence;
                    }
                }
            }

            base.fillScore = clamp01(bestFillEvidence);
            base.finalScore = fusedScore(base.contrastScore, base.fillScore, base.geometryScore, true);
        }
    }

    private static List<ShapeGeometryClassifier.ShapeCandidate> selectContrastMain(
            List<ShapeGeometryClassifier.ShapeCandidate> contrastCandidates
    ) {
        List<ShapeGeometryClassifier.ShapeCandidate> out = new ArrayList<>();
        if (contrastCandidates == null || contrastCandidates.isEmpty()) return out;

        Collections.sort(contrastCandidates, new Comparator<ShapeGeometryClassifier.ShapeCandidate>() {
            @Override
            public int compare(ShapeGeometryClassifier.ShapeCandidate a, ShapeGeometryClassifier.ShapeCandidate b) {
                return Double.compare(b.finalScore, a.finalScore);
            }
        });

        for (ShapeGeometryClassifier.ShapeCandidate c : contrastCandidates) {
            if (c == null || c.bbox == null) continue;
            if (c.type == ShapeDetector.ShapeType.UNKNOWN) continue;

            double minScore = (c.type == ShapeDetector.ShapeType.TRIANGLE) ? 0.40 : 0.55;
            if (c.finalScore < minScore) continue;

            boolean overlapped = false;
            for (ShapeGeometryClassifier.ShapeCandidate kept : out) {
                if (isSameObject(c, kept)) {
                    overlapped = true;
                    break;
                }
            }
            if (!overlapped) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<ShapeGeometryClassifier.ShapeCandidate> selectFillRescue(
            List<ShapeGeometryClassifier.ShapeCandidate> fillCandidates,
            List<ShapeGeometryClassifier.ShapeCandidate> selectedContrast
    ) {
        List<ShapeGeometryClassifier.ShapeCandidate> out = new ArrayList<>();
        if (fillCandidates == null || fillCandidates.isEmpty()) return out;

        for (ShapeGeometryClassifier.ShapeCandidate fc : fillCandidates) {
            if (fc == null || fc.bbox == null) continue;
            if (fc.type == ShapeDetector.ShapeType.UNKNOWN) continue;

            if (fc.finalScore < 0.60) continue;

            boolean overlapped = false;
            for (ShapeGeometryClassifier.ShapeCandidate cc : selectedContrast) {
                if (isSameObject(fc, cc)) {
                    overlapped = true;
                    break;
                }
            }

            if (!overlapped) {
                out.add(fc);
            }
        }

        return out;
    }

    private static List<ShapeGeometryClassifier.ShapeCandidate> dedupeFinal(
            List<ShapeGeometryClassifier.ShapeCandidate> in
    ) {
        List<ShapeGeometryClassifier.ShapeCandidate> out = new ArrayList<>();
        if (in == null || in.isEmpty()) return out;

        Collections.sort(in, new Comparator<ShapeGeometryClassifier.ShapeCandidate>() {
            @Override
            public int compare(ShapeGeometryClassifier.ShapeCandidate a, ShapeGeometryClassifier.ShapeCandidate b) {
                return Double.compare(b.finalScore, a.finalScore);
            }
        });

        for (ShapeGeometryClassifier.ShapeCandidate c : in) {
            if (c == null || c.bbox == null) continue;

            boolean overlapped = false;
            for (int i = 0; i < out.size(); i++) {
                ShapeGeometryClassifier.ShapeCandidate kept = out.get(i);
                if (!isSameObject(c, kept)) continue;

                if (preferOver(c, kept)) {
                    out.set(i, c);
                }
                overlapped = true;
                break;
            }

            if (!overlapped) {
                out.add(c);
            }
        }

        return out;
    }

    private static boolean preferOver(
            ShapeGeometryClassifier.ShapeCandidate a,
            ShapeGeometryClassifier.ShapeCandidate b
    ) {
        boolean aContrast = "contrast_main".equals(a.source);
        boolean bContrast = "contrast_main".equals(b.source);

        if (aContrast != bContrast) return aContrast;
        return a.finalScore > b.finalScore;
    }

    private static boolean isSameObject(
            ShapeGeometryClassifier.ShapeCandidate a,
            ShapeGeometryClassifier.ShapeCandidate b
    ) {
        if (a == null || b == null || a.bbox == null || b.bbox == null) return false;

        double iou = iou(a.bbox, b.bbox);
        if (iou >= 0.35) return true;

        if (a.center != null && b.center != null) {
            double dx = a.center.x - b.center.x;
            double dy = a.center.y - b.center.y;
            double dist = Math.sqrt(dx * dx + dy * dy);

            double ra = Math.sqrt(Math.max(1.0, a.bbox.width * a.bbox.width + a.bbox.height * a.bbox.height));
            double rb = Math.sqrt(Math.max(1.0, b.bbox.width * b.bbox.width + b.bbox.height * b.bbox.height));
            double norm = Math.max(1.0, Math.min(ra, rb));

            if (dist / norm < 0.30) return true;
        }

        return false;
    }

    private static double fusedScore(
            double contrastScore,
            double fillScore,
            double geometryScore,
            boolean contrastMain
    ) {
        double c = clamp01(contrastScore);
        double f = clamp01(fillScore);
        double g = clamp01(geometryScore / 2.8);

        if (contrastMain) {
            return (0.50 * c) + (0.15 * f) + (0.35 * g);
        } else {
            return (0.15 * c) + (0.50 * f) + (0.35 * g);
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static Mat grayMaskToRgba(Mat mask8u) {
        Mat rgba = new Mat();
        Imgproc.cvtColor(mask8u, rgba, Imgproc.COLOR_GRAY2RGBA);
        return rgba;
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

    /**
     * Draws the detected shape using its actual geometry:
     *  - CIRCLE  → ellipse fitted to bbox
     *  - others  → convex hull of the detected contour (or bbox fallback)
     */
    private static void drawShapeOutline(
            Mat disp,
            ShapeGeometryClassifier.ShapeCandidate s,
            Scalar color,
            int thick) {

        if (s.type == ShapeDetector.ShapeType.CIRCLE) {
            Point center = new Point(
                    s.bbox.x + s.bbox.width  / 2.0,
                    s.bbox.y + s.bbox.height / 2.0);
            Size axes = new Size(s.bbox.width / 2, s.bbox.height / 2);
            Imgproc.ellipse(disp, center, axes, 0, 0, 360, color, thick);
            return;
        }

        if (s.contour != null && !s.contour.empty()) {
            MatOfInt hullIdx = new MatOfInt();
            try {
                Imgproc.convexHull(s.contour, hullIdx);
                int[]   idx  = hullIdx.toArray();
                Point[] pts  = s.contour.toArray();
                Point[] hull = new Point[idx.length];
                for (int i = 0; i < idx.length; i++) hull[i] = pts[idx[i]];

                MatOfPoint hullMop = new MatOfPoint(hull);
                List<MatOfPoint> hullList = new ArrayList<>();
                hullList.add(hullMop);
                Imgproc.drawContours(disp, hullList, 0, color, thick);
                hullMop.release();
            } finally {
                hullIdx.release();
            }
            return;
        }

        // fallback
        Imgproc.rectangle(disp, s.bbox, color, thick);
    }

    private static void drawRotatedRect(Mat rgba, RotatedRect rr, Scalar color, int thick) {
        Point[] pts = new Point[4];
        rr.points(pts);
        for (int i = 0; i < 4; i++) {
            Imgproc.line(rgba, pts[i], pts[(i + 1) % 4], color, thick);
        }
    }

    public static Rect clampRect(Rect r, int W, int H) {
        int x = Math.max(0, Math.min(r.x, W - 1));
        int y = Math.max(0, Math.min(r.y, H - 1));
        int w = Math.max(1, Math.min(r.width, W - x));
        int h = Math.max(1, Math.min(r.height, H - y));
        return new Rect(x, y, w, h);
    }

    private static Mat erodeMask(Mat src, int kSize, int iter) {
        Mat out = new Mat();
        Mat k = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, new Size(kSize, kSize));
        Imgproc.erode(src, out, k, new Point(-1, -1), iter);
        k.release();
        return out;
    }

    private static boolean isInsideMask(Mat mask8u, Rect bbox) {
        if (mask8u == null || mask8u.empty() || bbox == null) return false;
        int cx = bbox.x + bbox.width / 2;
        int cy = bbox.y + bbox.height / 2;
        cx = Math.max(0, Math.min(mask8u.cols() - 1, cx));
        cy = Math.max(0, Math.min(mask8u.rows() - 1, cy));
        double[] v = mask8u.get(cy, cx);
        return v != null && v.length > 0 && v[0] > 0;
    }

    private static MatOfPoint rectContour(Rect r) {
        if (r == null || r.width <= 0 || r.height <= 0) return null;
        return new MatOfPoint(
                new Point(r.x, r.y),
                new Point(r.x + r.width, r.y),
                new Point(r.x + r.width, r.y + r.height),
                new Point(r.x, r.y + r.height)
        );
    }

    private static double iou(Rect a, Rect b) {
        if (a == null || b == null) return 0.0;

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

    private static boolean bboxContains(Rect r, Point p) {
        if (r == null || p == null) return false;
        return p.x >= r.x && p.x <= (r.x + r.width)
                && p.y >= r.y && p.y <= (r.y + r.height);
    }

    private static boolean sameKnownType(ShapeDetector.ShapeType a, ShapeDetector.ShapeType b) {
        if (a == null || b == null) return false;
        if (a == ShapeDetector.ShapeType.UNKNOWN || b == ShapeDetector.ShapeType.UNKNOWN) return false;
        return a == b;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static void releaseCandidateContours(List<ShapeGeometryClassifier.ShapeCandidate> list) {
        if (list == null) return;
        for (ShapeGeometryClassifier.ShapeCandidate c : list) {
            if (c != null && c.contour != null) {
                try {
                    c.contour.release();
                } catch (Exception ignore) {}
            }
        }
    }

    private static void releaseFillContours(List<ShapeGeometryClassifier.ShapeCandidate> list) {
        if (list == null) return;
        for (ShapeGeometryClassifier.ShapeCandidate c : list) {
            if (c != null && c.contour != null) {
                try {
                    c.contour.release();
                } catch (Exception ignore) {}
            }
        }
    }

    private static void releaseContrastContours(ShapeDetectorContrast.Result cr) {
        if (cr == null || cr.shapes == null) return;
        for (ShapeDetector.DetectedShape s : cr.shapes) {
            if (s != null && s.contour != null) {
                try {
                    s.contour.release();
                } catch (Exception ignore) {}
            }
        }
    }

    /**
     * For any quadrant whose expected shape type is absent from finalCandidates,
     * runs an OTSU-based dark-region search inside that quadrant's ROI.
     * This rescues shapes (e.g. the black textured square) that adaptive threshold
     * mis-detects as a hollow ring with low rectangularity → UNKNOWN → dropped.
     */
    private static List<ShapeGeometryClassifier.ShapeCandidate> rescueMissingQuadrants(
            Mat rgba,
            List<ShapeGeometryClassifier.ShapeCandidate> candidates,
            Mat diskMask,
            RotatedRect strip,
            Mat validMask) {

        if (rgba == null || rgba.empty()) return candidates;
        if (diskMask == null || diskMask.empty()) return candidates;

        Moments mom = Imgproc.moments(diskMask);
        if (mom.m00 < 1) return candidates;

        final int W = rgba.cols();
        final int H = rgba.rows();
        double discCx = mom.m10 / mom.m00;
        double discCy = mom.m01 / mom.m00;
        double divX = (strip != null) ? strip.center.x : discCx;
        double divY = discCy;

        // Collect which types are already detected
        Set<ShapeDetector.ShapeType> present = new HashSet<>();
        for (ShapeGeometryClassifier.ShapeCandidate c : candidates) {
            if (c != null && c.type != null && c.type != ShapeDetector.ShapeType.UNKNOWN) {
                present.add(c.type);
            }
        }

        // Fixed layout: TL=TRIANGLE (pH), TR=CIRCLE (Uric acid), BL=SQUARE (Temp), BR=HEXAGON (Glucose)
        ShapeDetector.ShapeType[] types = {
                ShapeDetector.ShapeType.TRIANGLE,
                ShapeDetector.ShapeType.CIRCLE,
                ShapeDetector.ShapeType.SQUARE,
                ShapeDetector.ShapeType.HEXAGON
        };
        // Each quadrant: [x0, y0, x1, y1] (pixel bounds, clamped below)
        double[][] quadBounds = {
                {0,    0,    divX, divY},   // TL
                {divX, 0,    W,    divY},   // TR
                {0,    divY, divX, H   },   // BL
                {divX, divY, W,    H   }    // BR
        };

        Mat gray = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

        boolean anyAdded = false;

        for (int qi = 0; qi < 4; qi++) {
            ShapeDetector.ShapeType expected = types[qi];
            if (present.contains(expected)) continue;   // already found

            int qx = (int) Math.max(0, quadBounds[qi][0]);
            int qy = (int) Math.max(0, quadBounds[qi][1]);
            int qx2 = (int) Math.min(W, quadBounds[qi][2]);
            int qy2 = (int) Math.min(H, quadBounds[qi][3]);
            int qw = qx2 - qx;
            int qh = qy2 - qy;
            if (qw < 20 || qh < 20) continue;

            Rect quadRect = new Rect(qx, qy, qw, qh);

            // Extract gray ROI; compute disc mean brightness WITHIN validMask
            Mat grayRoi = gray.submat(quadRect);
            Mat quadGray = new Mat(qh, qw, CvType.CV_8UC1, new Scalar(0));
            double discMean = 180.0; // fallback

            if (validMask != null && !validMask.empty()) {
                Mat vmRoi = validMask.submat(quadRect);
                // Mean brightness of disc background pixels in this quadrant
                Scalar meanSc = Core.mean(grayRoi, vmRoi);
                discMean = (meanSc.val[0] > 10) ? meanSc.val[0] : 180.0;
                grayRoi.copyTo(quadGray, vmRoi);
                vmRoi.release();
            } else {
                grayRoi.copyTo(quadGray);
                discMean = Core.mean(quadGray).val[0];
            }
            grayRoi.release();

            // Threshold: shapes darker than 80 % of the disc background mean
            // (avoids OTSU being confused by zero-padded outside-disc pixels)
            double threshVal = discMean * 0.80;
            Mat bin = new Mat();
            Imgproc.threshold(quadGray, bin, threshVal, 255, Imgproc.THRESH_BINARY_INV);

            // Morphological close to fill interior texture gaps
            int mk = 7;
            Mat kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, new Size(mk, mk));
            Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE,
                    kernel, new Point(-1, -1), 2);
            kernel.release();

            // Keep only pixels inside the disc
            if (validMask != null && !validMask.empty()) {
                Mat vmRoi = validMask.submat(quadRect);
                Core.bitwise_and(bin, vmRoi, bin);
                vmRoi.release();
            }

            // Find contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hier = new Mat();
            Imgproc.findContours(bin, contours, hier,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            hier.release();

            double minArea = 0.003 * W * H;
            double maxArea = 0.25  * W * H;

            ShapeGeometryClassifier.ShapeCandidate best = null;
            double bestArea = -1;

            for (MatOfPoint c : contours) {
                double area = Imgproc.contourArea(c);
                if (area < minArea || area > maxArea) { c.release(); continue; }

                Rect bbox = Imgproc.boundingRect(c);
                if (bbox.width < 20 || bbox.height < 20) { c.release(); continue; }

                // Translate contour coords from quadrant-local to full-image
                MatOfPoint shifted = shiftContour(c, qx, qy);
                Rect fullBbox = new Rect(bbox.x + qx, bbox.y + qy, bbox.width, bbox.height);

                ShapeGeometryClassifier.ShapeCandidate cand =
                        new ShapeGeometryClassifier.ShapeCandidate();
                cand.type         = expected;
                cand.contour      = shifted;
                cand.bbox         = fullBbox;
                cand.center       = new Point(
                        fullBbox.x + fullBbox.width  / 2.0,
                        fullBbox.y + fullBbox.height / 2.0);
                cand.area         = area;
                cand.contrastScore = 0.50;
                cand.fillScore    = 0.00;
                cand.geometryScore = 1.50;
                cand.finalScore   = 0.50;
                cand.source       = "quad_rescue";

                if (area > bestArea) {
                    if (best != null && best.contour != null) best.contour.release();
                    best      = cand;
                    bestArea  = area;
                } else {
                    shifted.release();
                    c.release();
                }
            }

            if (best != null) {
                candidates.add(best);
                anyAdded = true;
                Log.d(TAG, String.format(Locale.US,
                        "QuadRescue: added %s bbox=%s area=%.1f",
                        expected, best.bbox.toString(), best.area));
            }

            // cleanup
            for (MatOfPoint c : contours) {
                try { c.release(); } catch (Exception ignored) {}
            }
            bin.release();
            quadGray.release();
        }

        gray.release();

        return anyAdded ? dedupeFinal(candidates) : candidates;
    }

    /** Shift all contour points by (dx, dy). */
    private static MatOfPoint shiftContour(MatOfPoint src, int dx, int dy) {
        Point[] pts = src.toArray();
        for (Point p : pts) { p.x += dx; p.y += dy; }
        return new MatOfPoint(pts);
    }

    /**
     * Quadrant-based shape type constraint.
     * Fixed patch layout:  TL=TRIANGLE (pH)   TR=CIRCLE (Uric acid)
     *                      BL=SQUARE (Temp)   BR=HEXAGON (Glucose)
     * Divider: strip.center.x (horizontal), disc centroid Y (vertical).
     * Matching type → score +0.15; non-matching → type overridden, score -0.10.
     */
    private static List<ShapeGeometryClassifier.ShapeCandidate> applyQuadrantConstraint(
            List<ShapeGeometryClassifier.ShapeCandidate> candidates,
            Mat diskMask,
            RotatedRect strip) {

        if (candidates == null || candidates.isEmpty()) return candidates;
        if (diskMask == null || diskMask.empty()) return candidates;

        Moments mom = Imgproc.moments(diskMask);
        if (mom.m00 < 1) return candidates;

        double discCx = mom.m10 / mom.m00;
        double discCy = mom.m01 / mom.m00;
        double divX = (strip != null) ? strip.center.x : discCx;
        double divY = discCy;

        for (ShapeGeometryClassifier.ShapeCandidate c : candidates) {
            if (c == null || c.bbox == null) continue;

            double cx = c.bbox.x + c.bbox.width  / 2.0;
            double cy = c.bbox.y + c.bbox.height / 2.0;

            ShapeDetector.ShapeType expected;
            if      (cx < divX && cy < divY)  expected = ShapeDetector.ShapeType.TRIANGLE;
            else if (cx >= divX && cy < divY) expected = ShapeDetector.ShapeType.CIRCLE;
            else if (cx < divX && cy >= divY) expected = ShapeDetector.ShapeType.SQUARE;
            else                              expected = ShapeDetector.ShapeType.HEXAGON;

            if (c.type == expected) {
                c.finalScore = Math.min(1.0, c.finalScore + 0.15);
                c.source = c.source + "|quad✓";
            } else {
                Log.d(TAG, String.format(Locale.US,
                        "QuadConstraint: %s→%s at(%.0f,%.0f)",
                        c.type, expected, cx, cy));
                c.type = expected;
                c.finalScore = Math.max(0.0, c.finalScore - 0.10);
                c.source = c.source + "|quad✗→" + expected.name();
            }
        }

        return dedupeFinal(candidates);
    }

    private static void sampleCandidateColors(
            Mat rgba,
            List<ShapeGeometryClassifier.ShapeCandidate> candidates
    ) {
        if (rgba == null || rgba.empty() || candidates == null) return;

        for (ShapeGeometryClassifier.ShapeCandidate c : candidates) {
            if (c == null || c.bbox == null) continue;

            Rect r = clampRect(c.bbox, rgba.cols(), rgba.rows());
            if (r.width <= 0 || r.height <= 0) continue;

            // bbox 중심 20% 영역만 샘플링 (테두리 노이즈 제거)
            int cx = r.x + r.width / 2;
            int cy = r.y + r.height / 2;
            int sw = Math.max(1, r.width / 5);
            int sh = Math.max(1, r.height / 5);

            Rect inner = clampRect(
                    new Rect(cx - sw / 2, cy - sh / 2, sw, sh),
                    rgba.cols(), rgba.rows()
            );

            Mat roi = rgba.submat(inner);
            Scalar mean = Core.mean(roi);
            roi.release();

            c.meanR = mean.val[0];
            c.meanG = mean.val[1];
            c.meanB = mean.val[2];
        }
    }
}