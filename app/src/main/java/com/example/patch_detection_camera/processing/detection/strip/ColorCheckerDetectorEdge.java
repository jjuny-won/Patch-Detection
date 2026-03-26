package com.example.patch_detection_camera.processing.detection.strip;

import android.util.Log;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

public class ColorCheckerDetectorEdge {

    private static final String LOGT = "StripEdge";

    public static class Options {
        public boolean debug = true;
        public boolean keepDebugMats = true;

        public double detectScale = 2.0;

        // --- (fallback) edge 기반용 ---
        public double canny1 = 50;
        public double canny2 = 150;

        // --- black mask 기반 후보 생성용 ---
        public int blackVMax = 130;
        public int blackSMax = 255;    // 검정은 채도 낮은 경우도 있지만, 조명/카메라에 따라 넓게 허용
        public int blackCloseK = 5;    // 검정 테두리 연결
        public int blackOpenK  = 3;    // 노이즈 제거
        public int blackCloseIter = 2;

        // edge morph (fallback/debug)
        public int closeK = 7;
        public int openK  = 3;

        public double minAreaRatio = 0.0030;
        public double maxAreaRatio = 0.12;

        // 스트립 길쭉함
        public double minAspect = 2.50;
        public double maxAspect = 8.0;

        public double minRectangularity = 0.30;

        // 테두리 ring 두께 비율
        public float borderBandRatio = 0.12f;

        public double minBorderBlackRatio = 0.35;

        public boolean useCenterPrior = true;
        public double centerWeight = 2.0;
        public double centerSigma = 0.35;

        public boolean requireVertical = true;
        public boolean useVerticalPrior = true;
        public double verticalWeight = 1.0;

        public boolean logNonZero = true;

        // 내부 5분할 구조 검증 (지금은 꺼둠)
        public boolean verifyInnerBands = false;
        public double bandWeight = 5.0;
        public double minBandScore = 4.0;

        public double rrScaleForBands = 1.10;
        public int bandWarpW = 140;
        public int bandWarpH = 420;

        // 후보가 black-mask에서 안 나오면 edges로 fallback 할지
        public boolean fallbackToEdgesIfNoBlackContours = true;

        // vertical 판정 허용 오차(도)
        public double verticalTolDeg = 30.0; // 25~35 추천
    }

    public static class Result {
        public final RotatedRect rect;     // orig coords
        public final Point[] corners;      // TL,TR,BR,BL (orig coords)
        public final Mat debugMorph;       // 후보 생성 마스크(black morph or edge morph)
        public final Mat debugEdges;       // edges (optional)

        public Result(RotatedRect rect, Point[] corners, Mat debugMorph, Mat debugEdges) {
            this.rect = rect;
            this.corners = corners;
            this.debugMorph = debugMorph;
            this.debugEdges = debugEdges;
        }
    }

    public static Result detect(Mat rgba, Options opt) {
        if (rgba == null || rgba.empty()) return null;
        if (opt == null) opt = new Options();

        final double scale = (opt.detectScale <= 0 ? 1.0 : opt.detectScale);

        Mat work = rgba;
        boolean usedScaled = false;

        if (scale != 1.0) {
            work = new Mat();
            Imgproc.resize(rgba, work, new Size(), scale, scale, Imgproc.INTER_CUBIC);
            usedScaled = true;
            if (opt.debug) Log.d(LOGT, "detectScale=" + scale + " work=" + work.cols() + "x" + work.rows());
        }

        Mat bgr = new Mat();
        Mat hsv = new Mat();

        Mat black = new Mat();       // raw black
        Mat blackMorph = new Mat();  // morph black (candidate source)

        Mat gray = new Mat();
        Mat edges = new Mat();
        Mat edgeMorph = new Mat();

        Mat kClose = null, kOpen = null;
        Mat kBClose = null, kBOpen = null;

        try {
            // -----------------------
            // 1) black-mask 후보 생성
            // -----------------------
            Imgproc.cvtColor(work, bgr, Imgproc.COLOR_RGBA2BGR);
            Imgproc.GaussianBlur(bgr, bgr, new Size(3, 3), 0);
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);

            Core.inRange(hsv,
                    new Scalar(0, 0, 0),
                    new Scalar(179, opt.blackSMax, opt.blackVMax),
                    black);

            blackMorph = black.clone();

            int bCloseK = (opt.blackCloseK % 2 == 0) ? opt.blackCloseK + 1 : opt.blackCloseK;
            int bOpenK  = (opt.blackOpenK  % 2 == 0) ? opt.blackOpenK  + 1 : opt.blackOpenK;

            kBClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(bCloseK, bCloseK));
            kBOpen  = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(bOpenK, bOpenK));

            Imgproc.morphologyEx(blackMorph, blackMorph, Imgproc.MORPH_CLOSE, kBClose, new Point(-1,-1), opt.blackCloseIter);
            Imgproc.morphologyEx(blackMorph, blackMorph, Imgproc.MORPH_OPEN,  kBOpen,  new Point(-1,-1), 1);

            if (opt.debug && opt.logNonZero) {
                Log.d(LOGT, "black nz=" + Core.countNonZero(black) + " blackMorph nz=" + Core.countNonZero(blackMorph));
            }

            // -----------------------
            // 2) (fallback) edges도 계산 (debug/backup)
            // -----------------------
            Imgproc.cvtColor(work, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);

            Imgproc.Canny(gray, edges, opt.canny1, opt.canny2);

            kClose = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(opt.closeK, opt.closeK));
            Imgproc.morphologyEx(edges, edgeMorph, Imgproc.MORPH_CLOSE, kClose);
            kOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(opt.openK, opt.openK));
            Imgproc.morphologyEx(edgeMorph, edgeMorph, Imgproc.MORPH_OPEN, kOpen);

            if (opt.debug && opt.logNonZero) {
                Log.d(LOGT, "edges nz=" + Core.countNonZero(edges) + " edgeMorph nz=" + Core.countNonZero(edgeMorph));
            }

            // -----------------------
            // 3) contours는 blackMorph에서 우선 추출
            // -----------------------
            List<MatOfPoint> contours = new ArrayList<>();
            Imgproc.findContours(blackMorph.clone(), contours, new Mat(),
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            boolean usedEdgeFallback = false;
            if (contours.isEmpty() && opt.fallbackToEdgesIfNoBlackContours) {
                usedEdgeFallback = true;
                if (opt.debug) Log.d(LOGT, "No black contours -> fallback to edgeMorph contours");
                Imgproc.findContours(edgeMorph.clone(), contours, new Mat(),
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            }

            double imgArea = work.size().area();
            double minA = imgArea * opt.minAreaRatio;
            double maxA = imgArea * opt.maxAreaRatio;

            if (opt.debug) Log.d(LOGT, "contours=" + contours.size() + " imgArea=" + imgArea
                    + (usedEdgeFallback ? " (EDGE fallback)" : " (BLACK)"));

            double bestScore = -1e18;
            Point[] bestCorners = null;
            RotatedRect bestRR = null;

            double cx0 = work.cols() * 0.5;
            double cy0 = work.rows() * 0.5;
            double normDiag = Math.sqrt(cx0 * cx0 + cy0 * cy0);

            for (int idx = 0; idx < contours.size(); idx++) {
                MatOfPoint c = contours.get(idx);
                double area = Imgproc.contourArea(c);

                double aspect = 0.0;
                double recta = 0.0;
                double sBorder = 0.0;
                double sBand = 0.0;

                MatOfPoint2f c2 = new MatOfPoint2f(c.toArray());
                RotatedRect rr = Imgproc.minAreaRect(c2);

                if (area < minA || area > maxA) {
                    reject(opt, idx, "AREA", area, rr, aspect, recta, sBorder, sBand);
                    c2.release();
                    c.release();
                    continue;
                }

                // ✅ 정규화(rr의 w/h/angle 규칙 차이 흡수)
                NormRR nrr = normalizeRR(rr);

                double w = nrr.w;
                double h = nrr.h;
                aspect = nrr.aspect;

                // 디버그: 실제로 angle이 어떻게 나오나 확인
                if (opt.debug) {
                    Log.d(LOGT, String.format(
                            "#%d preCheck raw(w=%.1f h=%.1f a=%.1f) norm(w=%.1f h=%.1f ang=%.1f) aspect=%.2f",
                            idx, rr.size.width, rr.size.height, rr.angle, w, h, nrr.angleDeg, aspect
                    ));
                }

                boolean verticalish = isVertical(nrr, opt.verticalTolDeg);

                if (opt.requireVertical && !verticalish) {
//                    reject(opt, idx, "NOT_VERTICAL", area, rr, aspect, recta, sBorder, sBand);
                    c2.release();
                    c.release();
                    continue;
                }

                if (aspect < opt.minAspect || aspect > opt.maxAspect) {
                    reject(opt, idx, "ASPECT", area, rr, aspect, recta, sBorder, sBand);
                    c2.release();
                    c.release();
                    continue;
                }

                double rrArea = (w * h) + 1e-6;
                recta = area / rrArea;
                if (recta < opt.minRectangularity) {
                    reject(opt, idx, "RECTANGULARITY", area, rr, aspect, recta, sBorder, sBand);
                    c2.release();
                    c.release();
                    continue;
                }

                Point[] rrPts = new Point[4];
                rr.points(rrPts);
                Point[] ordered = orderQuad(rrPts);

                sBorder = scoreBlackBorderRatio(work, ordered, opt.borderBandRatio, opt.blackVMax, opt.blackSMax);
                if (sBorder < opt.minBorderBlackRatio) {
                    reject(opt, idx, "BORDER_BLACK", area, rr, aspect, recta, sBorder, sBand);
                    c2.release();
                    c.release();
                    continue;
                }

                double sCenter = 0.0;
                if (opt.useCenterPrior) {
                    double dx = rr.center.x - cx0;
                    double dy = rr.center.y - cy0;
                    double d = Math.sqrt(dx * dx + dy * dy) / (normDiag + 1e-9);
                    double sigma = Math.max(1e-6, opt.centerSigma);
                    sCenter = Math.exp(- (d * d) / (sigma * sigma));
                }

                double sVert = 0.0;
                if (opt.useVerticalPrior) {
                    sVert = verticalish ? 1.0 : 0.0;
                }

                // 내부 5분할은 현재 off (혹시 켜도 reject는 안 하도록 유지 가능)
                if (opt.verifyInnerBands) {
                    RotatedRect rrBand = new RotatedRect(
                            rr.center,
                            new Size(rr.size.width * opt.rrScaleForBands, rr.size.height * opt.rrScaleForBands),
                            rr.angle
                    );

                    ColorStripSampler.StripSample ss = null;
                    try {
                        ss = ColorStripSampler.sampleFiveBands(work, rrBand, opt.bandWarpW, opt.bandWarpH);
                        if (ss != null && ss.warped != null && !ss.warped.empty()) {
                            StripInnerBlockDetector.Options bop = new StripInnerBlockDetector.Options();
                            StripInnerBlockDetector.Result br = StripInnerBlockDetector.detectBands(ss.warped, bop);
                            sBand = scoreBandsStrictVertical(br, ss.warped);
                        } else {
                            sBand = 0.0;
                        }
                    } catch (Exception e) {
                        sBand = 0.0;
                    } finally {
                        if (ss != null && ss.warped != null) ss.warped.release();
                    }

                    if (sBand < opt.minBandScore) {
                        reject(opt, idx, "BAND_SCORE(IGNORED)", area, rr, aspect, recta, sBorder, sBand);
                        // continue;  // 일부러 막지 않음
                    }
                }

                // 최종 점수
                double score = (sBorder * 3.0)
                        + opt.centerWeight * sCenter
                        + opt.verticalWeight * sVert
                        + (recta * 0.5);

                if (opt.debug) {
                    Rect br2 = Imgproc.boundingRect(new MatOfPoint(ordered));
                    Log.d(LOGT, "#" + idx + " PASS rect=" + br2
                            + " area=" + String.format("%.0f", area)
                            + " aspect=" + String.format("%.2f", aspect)
                            + " recta=" + String.format("%.2f", recta)
                            + " sBorderRatio=" + String.format("%.3f", sBorder)
                            + " sCenter=" + String.format("%.3f", sCenter)
                            + " sVert=" + String.format("%.1f", sVert)
                            + " sBand=" + String.format("%.1f", sBand)
                            + " => score=" + String.format("%.3f", score));
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestCorners = ordered;
                    bestRR = rr;
                }

                c2.release();
                c.release();
            }

            // scale back to orig coords
            if (bestCorners != null && usedScaled) {
                for (Point p : bestCorners) {
                    p.x /= scale;
                    p.y /= scale;
                }
                bestRR = scaleRotatedRect(bestRR, 1.0 / scale);
            }

            // debug mats ownership
            Mat debugEdges;
            Mat debugMorph;
            if (opt.keepDebugMats) {
                debugEdges = edges;
                debugMorph = usedEdgeFallback ? edgeMorph : blackMorph;

                if (usedEdgeFallback) {
                    blackMorph.release();
                } else {
                    edgeMorph.release();
                }
            } else {
                debugEdges = new Mat();
                debugMorph = new Mat();
                edges.release();
                blackMorph.release();
                edgeMorph.release();
            }

            if (bestCorners == null) {
                if (opt.debug) Log.d(LOGT, "BEST: null (no strip). returning debug mats");
                return new Result(null, null, debugMorph, debugEdges);
            }

            if (opt.debug) Log.d(LOGT, "BEST: score=" + String.format("%.3f", bestScore)
                    + " rr=" + bestRR.center + " size=" + bestRR.size);

            return new Result(bestRR, bestCorners, debugMorph, debugEdges);

        } finally {
            // release locals
            bgr.release();
            hsv.release();
            black.release();

            gray.release();

            if (kClose != null) kClose.release();
            if (kOpen != null) kOpen.release();
            if (kBClose != null) kBClose.release();
            if (kBOpen != null) kBOpen.release();

            // keepDebugMats=true일 때 edges/blackMorph/edgeMorph는 Result가 소유할 수 있음.
            // 위에서 분기 처리했으니 여기서는 release 하지 않음.

            if (usedScaled && work != null) work.release();
        }
    }

    private static RotatedRect scaleRotatedRect(RotatedRect rr, double s) {
        if (rr == null) return null;
        return new RotatedRect(
                new Point(rr.center.x * s, rr.center.y * s),
                new Size(rr.size.width * s, rr.size.height * s),
                rr.angle
        );
    }

    // -----------------------
    // Border score: black ratio in ring (0~1)
    // -----------------------
    private static double scoreBlackBorderRatio(Mat rgba, Point[] quadTLTRBRBL, float bandRatio,
                                                int blackVMax, int blackSMax) {
        Mat warped = warpToRect(rgba, quadTLTRBRBL, 220, 80);

        Mat bgr = new Mat();
        Imgproc.cvtColor(warped, bgr, Imgproc.COLOR_RGBA2BGR);

        Mat hsv = new Mat();
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);

        Mat black = new Mat();
        Core.inRange(hsv, new Scalar(0, 0, 0), new Scalar(179, blackSMax, blackVMax), black);

        int W = black.cols(), H = black.rows();
        int band = Math.max(2, Math.round(Math.min(W, H) * bandRatio));
        band = Math.max(1, Math.min(band, Math.min(W / 2, H / 2)));

        Mat ring = Mat.zeros(black.size(), CvType.CV_8UC1);
        Imgproc.rectangle(ring, new Point(0, 0), new Point(W - 1, H - 1), new Scalar(255), -1);
        Imgproc.rectangle(ring, new Point(band, band), new Point(W - 1 - band, H - 1 - band), new Scalar(0), -1);

        Mat onlyRing = new Mat();
        Core.bitwise_and(black, ring, onlyRing);

        double ringArea = Math.max(1.0, Core.countNonZero(ring));
        double blackInRing = Core.countNonZero(onlyRing);
        double ratio = blackInRing / ringArea;

        onlyRing.release();
        ring.release();
        black.release();
        hsv.release();
        bgr.release();
        warped.release();

        return ratio;
    }

    // ---------- RotatedRect normalize ----------
    static class NormRR {
        RotatedRect rr;
        double w, h;      // w <= h 로 정규화
        double angleDeg;  // "긴 변" 기준 각도 (0~180)
        double aspect;    // h/w (>=1)
    }

    static NormRR normalizeRR(RotatedRect rr) {
        NormRR n = new NormRR();
        n.rr = rr;

        double w = rr.size.width;
        double h = rr.size.height;
        double a = rr.angle;

        // 긴 변이 h가 되도록 정규화
        if (w > h) {
            double tmp = w; w = h; h = tmp;
            a = a + 90.0;
        }

        // 각도를 0~180 범위로 정리
        a = a % 180.0;
        if (a < 0) a += 180.0;

        n.w = w;
        n.h = h;
        n.aspect = h / Math.max(1e-6, w);
        n.angleDeg = a;
        return n;
    }

    /**
     * 환경에 따라 "세로"가 90도 근처로 나오기도 하고, 0도 근처로 나오기도 함.
     * 그래서 둘 다 허용.
     */
    static boolean isVertical(NormRR n, double tolDeg) {
        double a = n.angleDeg % 180.0;
        double d90 = Math.abs(a - 90.0);
        double d0 = Math.min(Math.abs(a - 0.0), Math.abs(a - 180.0));
        return (d90 <= tolDeg) || (d0 <= tolDeg);
    }

    private static Mat warpToRect(Mat rgba, Point[] quadTLTRBRBL, int outW, int outH) {
        MatOfPoint2f src = new MatOfPoint2f(quadTLTRBRBL);
        MatOfPoint2f dst = new MatOfPoint2f(
                new Point(0, 0),
                new Point(outW - 1, 0),
                new Point(outW - 1, outH - 1),
                new Point(0, outH - 1)
        );
        Mat M = Imgproc.getPerspectiveTransform(src, dst);
        Mat out = new Mat();
        Imgproc.warpPerspective(rgba, out, M, new Size(outW, outH));
        M.release();
        src.release();
        dst.release();
        return out;
    }

    // ----- band 검증 -----
    private static double scoreBandsStrictVertical(StripInnerBlockDetector.Result rr, Mat warped) {
        if (rr == null || rr.bands == null || rr.bands.size() < 5) return 0.0;

        int prevY = -1;
        int refH = -1;

        for (int i = 0; i < 5; i++) {
            StripInnerBlockDetector.BandResult br = rr.bands.get(i);
            if (br == null) return 0.0;

            Rect bandR = clamp(br.bandRect, warped.cols(), warped.rows());
            Rect innerR = clamp(br.innerRect, warped.cols(), warped.rows());

            if (innerR.width < 10 || innerR.height < 10) return 0.0;
            if (!contains(bandR, innerR)) return 0.0;

            if (prevY != -1 && bandR.y <= prevY) return 0.0;
            prevY = bandR.y;

            if (refH == -1) refH = bandR.height;
            else {
                double ratio = (double) bandR.height / Math.max(1, refH);
                if (ratio < 0.6 || ratio > 1.4) return 0.0;
            }

            double aRatio = innerR.area() / Math.max(1.0, bandR.area());
            if (aRatio < 0.15 || aRatio > 0.90) return 0.0;
        }

        return 5.0;
    }

    private static boolean contains(Rect outer, Rect inner) {
        return inner.x >= outer.x && inner.y >= outer.y
                && (inner.x + inner.width) <= (outer.x + outer.width)
                && (inner.y + inner.height) <= (outer.y + outer.height);
    }

    private static Rect clamp(Rect r, int W, int H) {
        int x = Math.max(0, Math.min(r.x, W - 1));
        int y = Math.max(0, Math.min(r.y, H - 1));
        int w = Math.max(1, Math.min(r.width, W - x));
        int h = Math.max(1, Math.min(r.height, H - y));
        return new Rect(x, y, w, h);
    }

    private static Point[] orderQuad(Point[] pts) {
        Point tl = null, tr = null, br = null, bl = null;
        double minSum = 1e18, maxSum = -1e18;
        double minDiff = 1e18, maxDiff = -1e18;

        for (Point p : pts) {
            double s = p.x + p.y;
            double d = p.x - p.y;
            if (s < minSum) { minSum = s; tl = p; }
            if (s > maxSum) { maxSum = s; br = p; }
            if (d < minDiff) { minDiff = d; bl = p; }
            if (d > maxDiff) { maxDiff = d; tr = p; }
        }
        return new Point[]{ tl, tr, br, bl };
    }

    private static void reject(Options opt, int idx, String reason, double area, RotatedRect rr,
                               double aspect, double recta, double sBorder, double sBand) {
        if (opt == null || !opt.debug) return;

        String rrStr = (rr == null) ? "rr=null"
                : String.format("rr(c=%.0f,%.0f w=%.0f h=%.0f a=%.1f)",
                rr.center.x, rr.center.y, rr.size.width, rr.size.height, rr.angle);

        Log.d(LOGT, String.format(
                "#%d REJECT %-16s area=%.0f aspect=%.2f recta=%.2f sBorder=%.3f sBand=%.2f %s",
                idx, reason, area, aspect, recta, sBorder, sBand, rrStr
        ));
    }
}