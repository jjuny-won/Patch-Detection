package com.example.patch_detection_camera.processing.detection.shape;

import android.util.Log;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.Locale;

public class ShapeGeometryClassifier {

    private static final String TAG = "ShapeGeo";

    public static class ShapeCandidate {
        public ShapeDetector.ShapeType type;
        public MatOfPoint contour;
        public Rect bbox;
        public RotatedRect rbox;
        public Point center;
        public double area;

        public double contrastScore;
        public double fillScore;
        public double geometryScore;
        public double finalScore;

        public String source;

        public double meanR;
        public double meanG;
        public double meanB;
    }

    public static class Options {
        public boolean debug = true;

        // polygon approximation on hull
        public double approxEpsRatio = 0.035;

        // circle rules
        public double minCircularity = 0.93;
        public double circleArLo = 0.85;
        public double circleArHi = 1.20;
        public double circleMaxRRatio = 1.20;

        // 6-vertex special case
        public double circleLikeHexCirc = 0.96;
        public double circleLikeHexRRatio = 1.15;

        // 7-vertex special case
        public double circleLike7Circ = 0.985;
        public double circleLike7RRatio = 1.05;

        // triangle / square split
        public double triangle3SquareArMax = 1.15;
        public double triangle3SquareRRatioMax = 1.15;
        public double triangle3SquareRectMin = 0.68;

        public double quadTriangleRectMax = 0.78;
        public double quadTriangleCircMax = 0.88;

        // hex / square / unknown split
        public double hexMaxRRatio = 1.60;
        public double pentagonSquareRRatioMax = 1.25;

        // high-vertex circle fallback
        public int circleHighVertexMin = 9;
        public double circleHighVertexCircMin = 0.96;

        // slot hint
        public ShapeDetector.ShapeType expectedTypeHint = null;
        public double expectedTypeBonus = 0.45;

        // top1, top2가 너무 가까우면 hint 쪽을 우선
        public double ambiguityMargin = 0.25;

        // hint 타입이 너무 말이 안 되면 강제하지 않음
        public double minHintScoreToAccept = 0.80;
    }

    public static class Features {
        public Rect bbox;
        public RotatedRect rbox;
        public Point center;

        public double area;
        public double perimeter;

        public int vertices;
        public double circularity;
        public double rRatio;
        public double rectangularity;  // contourArea / rotatedRectArea
        public double bboxAspectRatio; // max(w/h, h/w)
    }

    public static class Result {
        public ShapeDetector.ShapeType type = ShapeDetector.ShapeType.UNKNOWN;
        public double score = 0.0;
        public Features features;

        public double triangleScore;
        public double squareScore;
        public double hexagonScore;
        public double circleScore;

        public MatOfPoint hull;       // caller releases
        public MatOfPoint2f approx;   // caller releases
        public RotatedRect rbox;
        public Point center;
    }

    public static Result classifyFromContour(MatOfPoint contour, Options opt) {
        if (contour == null || contour.empty()) return null;
        if (opt == null) opt = new Options();

        Result out = new Result();

        MatOfPoint hull = convexHullPoints(contour);
        MatOfPoint2f hull2f = new MatOfPoint2f(hull.toArray());

        double area = Math.abs(Imgproc.contourArea(hull));
        double peri = Imgproc.arcLength(hull2f, true);

        if (area < 1e-6 || peri < 1e-6) {
            hull.release();
            hull2f.release();
            return null;
        }

        MatOfPoint2f approx = new MatOfPoint2f();
        Imgproc.approxPolyDP(hull2f, approx, opt.approxEpsRatio * peri, true);

        Rect bbox = Imgproc.boundingRect(hull);
        RotatedRect rbox = Imgproc.minAreaRect(hull2f);
        Point center = contourCentroid(hull);

        double rw = Math.max(1e-6, rbox.size.width);
        double rh = Math.max(1e-6, rbox.size.height);
        double rRatio = Math.max(rw, rh) / Math.min(rw, rh);

        double circularity = 4.0 * Math.PI * area / (peri * peri + 1e-6);
        double rrArea = Math.max(1e-6, rw * rh);
        double rectangularity = Math.min(1.0, area / rrArea);

        double bw = Math.max(1.0, bbox.width);
        double bh = Math.max(1.0, bbox.height);
        double bboxAspectRatio = Math.max(bw, bh) / Math.min(bw, bh);

        Features f = new Features();
        f.bbox = bbox;
        f.rbox = rbox;
        f.center = center;
        f.area = area;
        f.perimeter = peri;
        f.vertices = approx.toArray().length;
        f.circularity = circularity;
        f.rRatio = rRatio;
        f.rectangularity = rectangularity;
        f.bboxAspectRatio = bboxAspectRatio;

        RankedResult ranked = classifyRanked(f, opt);

        out.type = ranked.type;
        out.score = ranked.score;
        out.features = f;

        out.triangleScore = ranked.triangleScore;
        out.squareScore   = ranked.squareScore;
        out.hexagonScore  = ranked.hexagonScore;
        out.circleScore   = ranked.circleScore;

        out.hull = hull;
        out.approx = approx;
        out.rbox = rbox;
        out.center = center;

        if (opt.debug) {
            Log.d(TAG, String.format(
                    Locale.US,
                    "v=%d circ=%.3f rRatio=%.3f rect=%.3f bboxAR=%.3f area=%.1f => %s score=%.3f " +
                            "[tri=%.3f sq=%.3f hex=%.3f cir=%.3f hint=%s]",
                    f.vertices,
                    f.circularity,
                    f.rRatio,
                    f.rectangularity,
                    f.bboxAspectRatio,
                    f.area,
                    out.type.name(),
                    out.score,
                    ranked.triangleScore,
                    ranked.squareScore,
                    ranked.hexagonScore,
                    ranked.circleScore,
                    (opt.expectedTypeHint != null ? opt.expectedTypeHint.name() : "null")
            ));
        }

        hull2f.release();
        return out;
    }

    public static ShapeCandidate toCandidate(
            MatOfPoint contour,
            Result gr,
            String source,
            double contrastScore,
            double fillScore
    ) {
        if (contour == null || contour.empty() || gr == null || gr.features == null) {
            return null;
        }

        ShapeCandidate c = new ShapeCandidate();
        c.type = gr.type;
        c.contour = contour;
        c.bbox = gr.features.bbox;
        c.rbox = gr.rbox;
        c.center = gr.center;
        c.area = gr.features.area;

        c.contrastScore = contrastScore;
        c.fillScore = fillScore;
        c.geometryScore = gr.score;

        // detector 단계에서는 geometry 비중을 가장 크게 두고,
        // source evidence는 보조적으로 반영
        c.finalScore =
                (0.40 * c.contrastScore) +
                        (0.20 * c.fillScore) +
                        (0.40 * c.geometryScore);

        c.source = source;
        return c;
    }

    private static class RankedResult {
        ShapeDetector.ShapeType type;
        double score;

        double triangleScore;
        double squareScore;
        double hexagonScore;
        double circleScore;
    }

    private static RankedResult classifyRanked(Features f, Options opt) {
        RankedResult rr = new RankedResult();

        rr.triangleScore = scoreTriangleCandidate(f, opt);
        rr.squareScore   = scoreSquareCandidate(f, opt);
        rr.hexagonScore  = scoreHexagonCandidate(f, opt);
        rr.circleScore   = scoreCircleCandidate(f, opt);

        // hint bonus
        if (opt.expectedTypeHint != null) {
            switch (opt.expectedTypeHint) {
                case TRIANGLE:
                    rr.triangleScore += opt.expectedTypeBonus;
                    break;
                case SQUARE:
                    rr.squareScore += opt.expectedTypeBonus;
                    break;
                case HEXAGON:
                    rr.hexagonScore += opt.expectedTypeBonus;
                    break;
                case CIRCLE:
                    rr.circleScore += opt.expectedTypeBonus;
                    break;
                default:
                    break;
            }
        }

        ShapeDetector.ShapeType bestType = ShapeDetector.ShapeType.UNKNOWN;
        double bestScore = 0.0;
        ShapeDetector.ShapeType secondType = ShapeDetector.ShapeType.UNKNOWN;
        double secondScore = 0.0;

        double[] vals = {
                rr.triangleScore,
                rr.squareScore,
                rr.hexagonScore,
                rr.circleScore
        };

        ShapeDetector.ShapeType[] types = {
                ShapeDetector.ShapeType.TRIANGLE,
                ShapeDetector.ShapeType.SQUARE,
                ShapeDetector.ShapeType.HEXAGON,
                ShapeDetector.ShapeType.CIRCLE
        };

        for (int i = 0; i < vals.length; i++) {
            double s = vals[i];
            if (s > bestScore) {
                secondScore = bestScore;
                secondType = bestType;
                bestScore = s;
                bestType = types[i];
            } else if (s > secondScore) {
                secondScore = s;
                secondType = types[i];
            }
        }

        // 애매하면 hint 우선
        if (opt.expectedTypeHint != null &&
                bestType != opt.expectedTypeHint &&
                (bestScore - secondScore) <= opt.ambiguityMargin) {

            double hintScore = getScoreForType(rr, opt.expectedTypeHint);
            if (hintScore >= opt.minHintScoreToAccept) {
                bestType = opt.expectedTypeHint;
                bestScore = hintScore;
            }
        }

        // 너무 낮으면 UNKNOWN
        if (bestScore < 0.75) {
            bestType = ShapeDetector.ShapeType.UNKNOWN;
            bestScore = 0.0;
        }

        rr.type = bestType;
        rr.score = bestScore;
        return rr;
    }

    private static double getScoreForType(RankedResult rr, ShapeDetector.ShapeType t) {
        switch (t) {
            case TRIANGLE: return rr.triangleScore;
            case SQUARE:   return rr.squareScore;
            case HEXAGON:  return rr.hexagonScore;
            case CIRCLE:   return rr.circleScore;
            default:       return 0.0;
        }
    }

    private static double scoreTriangleCandidate(Features f, Options opt) {
        if (f.vertices == 3) {
            return
                    1.4 +
                            (f.rectangularity * 0.8) +
                            Math.max(0.0, 1.25 - f.rRatio) * 0.5;
        }

        if (f.vertices == 4 &&
                f.rectangularity < opt.quadTriangleRectMax &&
                f.circularity < opt.quadTriangleCircMax) {
            return
                    0.95 +
                            (f.rectangularity * 0.5);
        }

        return 0.0;
    }

    private static double scoreSquareCandidate(Features f, Options opt) {
        double ratioScore = Math.max(0.0, 1.3 - Math.min(1.3, f.rRatio));
        double aspectScore = Math.max(0.0, 1.3 - Math.min(1.3, f.bboxAspectRatio));

        if (f.vertices == 4) {
            return
                    1.3 +
                            (f.rectangularity * 1.3) +
                            (ratioScore * 0.8) +
                            (aspectScore * 0.7);
        }

        if (f.vertices == 3) {
            boolean suspiciousSquareLike =
                    (f.bboxAspectRatio < opt.triangle3SquareArMax) &&
                            (f.rRatio < opt.triangle3SquareRRatioMax) &&
                            (f.rectangularity > opt.triangle3SquareRectMin);

            if (suspiciousSquareLike) {
                return
                        0.95 +
                                (f.rectangularity * 1.0) +
                                (ratioScore * 0.5);
            }
        }

        if (f.vertices == 5 && f.rRatio < opt.pentagonSquareRRatioMax) {
            return
                    0.85 +
                            (f.rectangularity * 0.9) +
                            (ratioScore * 0.5);
        }

        return 0.0;
    }

    private static double scoreHexagonCandidate(Features f, Options opt) {
        double ratioScore = Math.max(0.0, 1.3 - Math.min(1.3, f.rRatio));

        if (f.rRatio > opt.hexMaxRRatio) {
            return 0.0;
        }

        if (f.vertices == 6) {
            if (f.circularity >= opt.circleLikeHexCirc &&
                    f.rRatio < opt.circleLikeHexRRatio) {
                return 0.45 + (f.rectangularity * 0.5);
            }

            return
                    1.2 +
                            (f.rectangularity * 1.0) +
                            (ratioScore * 0.6);
        }

        if (f.vertices == 7) {
            if (f.circularity >= opt.circleLike7Circ &&
                    f.rRatio < opt.circleLike7RRatio) {
                return 0.35 + (f.rectangularity * 0.4);
            }

            return
                    0.95 +
                            (f.rectangularity * 0.9) +
                            (ratioScore * 0.5);
        }

        if (f.vertices == 5 && f.rRatio >= opt.pentagonSquareRRatioMax) {
            return
                    0.70 +
                            (f.rectangularity * 0.7);
        }

        return 0.0;
    }

    private static double scoreCircleCandidate(Features f, Options opt) {
        double ratioScore = Math.max(0.0, 1.3 - Math.min(1.3, f.rRatio));
        double aspectScore = Math.max(0.0, 1.3 - Math.min(1.3, f.bboxAspectRatio));

        boolean circleLike =
                (f.circularity >= opt.minCircularity) &&
                        (f.bboxAspectRatio >= opt.circleArLo && f.bboxAspectRatio <= opt.circleArHi) &&
                        (f.rRatio < opt.circleMaxRRatio);

        // v==6이면 더 엄격한 기준 적용
        // (v==6은 hexagon일 가능성이 높기 때문에, 원으로 보려면 거의 완벽한 원이어야 함)
        if (f.vertices == 6) {
            circleLike = circleLike &&
                    f.circularity >= opt.circleLikeHexCirc &&  // 0.96 이상
                    f.rRatio < opt.circleLikeHexRRatio;        // 1.15 미만
        }

        if (circleLike) {
            return 1.4 +
                    (f.circularity * 1.3) +
                    (ratioScore * 0.6) +
                    (aspectScore * 0.5);
        }

        // v==6이고 원에 가까우면 낮은 점수만 줌 (hexagon이 이기도록)
        if (f.vertices == 6 &&
                f.circularity >= opt.circleLikeHexCirc &&
                f.rRatio < opt.circleLikeHexRRatio) {
            return 1.05 +
                    (f.circularity * 1.0) +
                    (ratioScore * 0.4);
        }

        // v==7이고 원에 가까우면
        if (f.vertices == 7 &&
                f.circularity >= opt.circleLike7Circ &&
                f.rRatio < opt.circleLike7RRatio) {
            return 1.00 +
                    (f.circularity * 1.0) +
                    (ratioScore * 0.4);
        }

        // 꼭짓점이 많고 원형에 가까우면
        if (f.vertices >= opt.circleHighVertexMin &&
                f.circularity > opt.circleHighVertexCircMin) {
            return 1.1 +
                    (f.circularity * 1.1) +
                    (ratioScore * 0.4);
        }

        return 0.0;
    }

    private static MatOfPoint convexHullPoints(MatOfPoint contour) {
        MatOfInt hullIdx = new MatOfInt();
        Imgproc.convexHull(contour, hullIdx);

        Point[] pts = contour.toArray();
        int[] idx = hullIdx.toArray();
        Point[] hullPts = new Point[idx.length];
        for (int i = 0; i < idx.length; i++) {
            hullPts[i] = pts[idx[i]];
        }

        hullIdx.release();
        return new MatOfPoint(hullPts);
    }

    private static Point contourCentroid(MatOfPoint c) {
        Moments m = Imgproc.moments(c);
        double cx = (m.m00 != 0) ? (m.m10 / m.m00) : 0.0;
        double cy = (m.m00 != 0) ? (m.m01 / m.m00) : 0.0;
        return new Point(cx, cy);
    }
}