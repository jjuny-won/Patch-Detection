package com.example.patch_detection_camera.processing.detection.strip;

import android.util.Log;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.*;

public class ColorStripSampler {

    private static final String TAG = "ColorStripSampler";

    public enum Band { GRAY, RED, GREEN, BLUE, WHITE }

    public static class StripSample {
        public Mat warped;  // 디버그 확인용 (원하면 밖에서 release)
        public EnumMap<Band, Scalar> hsvMean = new EnumMap<>(Band.class);     // HSV mean
        public EnumMap<Band, Scalar> rgbMedian = new EnumMap<>(Band.class);   // RGB median (0~255)
        public EnumMap<Band, Rect> bandRects = new EnumMap<>(Band.class);     // warped에서 샘플링한 Rect
    }

    /**
     * rr(회전 사각형)로 strip을 정규화(warp)하고,
     * 세로 5등분 band를 자동 라벨링(Gray/R/G/B/White)한 뒤,
     * 각 band의 median RGB를 추출.
     *
     * @param rgba 원본 ROI/이미지
     * @param rr   ShapeDetector가 준 colorCheckerRBox
     * @param outW warp 결과 폭
     * @param outH warp 결과 높이 (세로가 긴 편이 샘플링 안정적)
     */
    public static StripSample sampleFiveBands(Mat rgba, RotatedRect rr, int outW, int outH) {
        StripSample out = new StripSample();

        // 1) warpPerspective로 strip 정규화
        Mat warped = warpByRotatedRect(rgba, rr, outW, outH);
        out.warped = warped;

        // 2) 실제 인쇄된 패치 경계(엣지/투영 분석)를 기준으로 5개 band Rect 생성
        List<Rect> bands = detectBandRects(warped);

        // 3) 각 band의 HSV mean + RGB median + 과다노출 비율 계산
        Scalar[] hsvMeans = new Scalar[5];
        Scalar[] rgbMeds  = new Scalar[5];
        BandLabelMatcher.BandStat[] stats = new BandLabelMatcher.BandStat[5];

        for (int i = 0; i < 5; i++) {
            Rect br = bands.get(i);

            hsvMeans[i] = meanHSV(warped, br);
            rgbMeds[i]  = medianRGB(warped, br); // RGB 순서 Scalar(r,g,b)

            BandLabelMatcher.BandStat st = new BandLabelMatcher.BandStat();
            st.meanH = hsvMeans[i].val[0];
            st.meanS = hsvMeans[i].val[1];
            st.meanV = hsvMeans[i].val[2];
            st.clippedRatio = clippedRatio(warped, br);
            stats[i] = st;
        }

        // 4) 브루트포스 순열 매칭: 색상 비용 + 위치 비용(정순/역순)을 합산해 전역 최적 배정
        BandLabelMatcher.MatchResult match = BandLabelMatcher.match(stats);
        Log.d(TAG, "BandLabelMatcher cost=" + match.totalCost
                + " reversed=" + match.usedReversedOrder
                + " whiteGrayAmbiguous=" + match.whiteGrayAmbiguous);

        // 5) 결과 맵에 담기
        for (int bandIdx = 0; bandIdx < 5; bandIdx++) {
            Band band = Band.valueOf(
                    BandLabelMatcher.LABELS[match.bandToLabelIndex[bandIdx]].toUpperCase(Locale.US));
            put(out, band, bandIdx, hsvMeans, rgbMeds, bands);
        }

        return out;
    }

    // 밴드 내 R,G,B 모두 SATURATION_V_THRESHOLD를 초과하는(과다노출) 픽셀 비율
    private static double clippedRatio(Mat rgba, Rect r) {
        Mat sub = rgba.submat(r);
        int step = Math.max(1, Math.min(r.width, r.height) / 30);

        int total = 0, clipped = 0;
        for (int yy = 0; yy < sub.rows(); yy += step) {
            for (int xx = 0; xx < sub.cols(); xx += step) {
                double[] px = sub.get(yy, xx); // RGBA
                total++;
                if (px[0] > BandLabelMatcher.SATURATION_V_THRESHOLD
                        && px[1] > BandLabelMatcher.SATURATION_V_THRESHOLD
                        && px[2] > BandLabelMatcher.SATURATION_V_THRESHOLD) {
                    clipped++;
                }
            }
        }

        sub.release();
        return total > 0 ? (double) clipped / total : 0.0;
    }

    // -------------------------
    // Warp by RotatedRect
    // -------------------------
    public static Mat warpByRotatedRect(Mat rgba, RotatedRect rr, int outW, int outH) {
        Point[] pts = new Point[4];
        rr.points(pts);

        // pts를 (tl,tr,br,bl)로 정렬
        Point[] ordered = orderQuadPoints(pts);
        Point tl = ordered[0], tr = ordered[1], br = ordered[2], bl = ordered[3];

        MatOfPoint2f src = new MatOfPoint2f(tl, tr, br, bl);
        MatOfPoint2f dst = new MatOfPoint2f(
                new Point(0, 0),
                new Point(outW - 1, 0),
                new Point(outW - 1, outH - 1),
                new Point(0, outH - 1)
        );

        Mat M = Imgproc.getPerspectiveTransform(src, dst);
        Mat warped = new Mat();
        Imgproc.warpPerspective(rgba, warped, M, new Size(outW, outH), Imgproc.INTER_LINEAR);

        M.release();
        src.release();
        dst.release();
        return warped;
    }

    // (tl,tr,br,bl) 정렬: sum/diff 방식
    private static Point[] orderQuadPoints(Point[] pts) {
        Point tl = null, tr = null, br = null, bl = null;

        double minSum = 1e18, maxSum = -1e18;
        double minDiff = 1e18, maxDiff = -1e18;

        for (Point p : pts) {
            double sum = p.x + p.y;
            double diff = p.x - p.y;

            if (sum < minSum) { minSum = sum; tl = p; }
            if (sum > maxSum) { maxSum = sum; br = p; }
            if (diff < minDiff) { minDiff = diff; bl = p; }
            if (diff > maxDiff) { maxDiff = diff; tr = p; }
        }
        return new Point[]{ tl, tr, br, bl };
    }

    // -------------------------
    // Band rects (5 vertical bands)
    // -------------------------

    // StripInnerBlockDetector의 엣지/투영 기반 경계 검출로 5개 band의 샘플링 영역(innerRect)을
    // 구한다. 검출이 불안정하면 StripInnerBlockDetector가 자체적으로 균등분할로 폴백하므로
    // 여기서는 항상 5개 Rect가 반환된다고 가정하되, 방어적으로 실패 시에도 균등분할로 대체한다.
    private static List<Rect> detectBandRects(Mat warped) {
        StripInnerBlockDetector.Options opt = new StripInnerBlockDetector.Options();
        opt.debug = false;
        StripInnerBlockDetector.Result res = StripInnerBlockDetector.detectBands(warped, opt);

        if (res == null || res.bands == null || res.bands.size() < 5) {
            Log.d(TAG, "StripInnerBlockDetector failed, falling back to equal split");
            return makeFiveVerticalBands(warped.cols(), warped.rows(), 0.10);
        }

        Log.d(TAG, "warped=" + warped.cols() + "x" + warped.rows()
                + " stripInnerRect=" + res.stripInnerRect
                + " cutsY=" + res.cutsY);

        List<Rect> rects = new ArrayList<>(5);
        for (int i = 0; i < res.bands.size(); i++) {
            StripInnerBlockDetector.BandResult br = res.bands.get(i);
            Log.d(TAG, "band" + i + " bandRect=" + br.bandRect + " innerRect=" + br.innerRect);
            rects.add(clampRect(br.innerRect, warped.cols(), warped.rows()));
        }
        return rects;
    }

    private static Rect clampRect(Rect r, int W, int H) {
        int x = Math.max(0, Math.min(r.x, W - 1));
        int y = Math.max(0, Math.min(r.y, H - 1));
        int w = Math.max(1, Math.min(r.width, W - x));
        int h = Math.max(1, Math.min(r.height, H - y));
        return new Rect(x, y, w, h);
    }

    private static List<Rect> makeFiveVerticalBands(int W, int H, double marginFrac) {
        int mx = (int)Math.round(W * marginFrac);
        int my = (int)Math.round(H * marginFrac);

        int x = clamp(mx, 0, W-1);
        int y = clamp(my, 0, H-1);
        int w = clamp(W - 2*mx, 1, W - x);
        int h = clamp(H - 2*my, 1, H - y);

        Rect inner = new Rect(x, y, w, h);

        int bandH = inner.height / 5;
        List<Rect> bands = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            int yy = inner.y + i * bandH;
            int hh = (i == 4) ? (inner.y + inner.height - yy) : bandH;
            bands.add(new Rect(inner.x, yy, inner.width, Math.max(1, hh)));
        }
        return bands;
    }

    // -------------------------
    // Stats
    // -------------------------
    private static Scalar meanHSV(Mat rgba, Rect r) {
        Mat sub = rgba.submat(r);
        Mat bgr = new Mat();
        Imgproc.cvtColor(sub, bgr, Imgproc.COLOR_RGBA2BGR);
        Mat hsv = new Mat();
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);

        Scalar m = Core.mean(hsv);

        sub.release();
        bgr.release();
        hsv.release();
        return m;
    }

    // median RGB: sub 영역에서 (R,G,B) 각각 median.
    // 속도 위해 step 샘플링(예: 2~3픽셀 간격) 적용.
    private static Scalar medianRGB(Mat rgba, Rect r) {
        Mat sub = rgba.submat(r);

        int step = Math.max(1, Math.min(r.width, r.height) / 30); // 대략 30x30 정도 샘플
        ArrayList<Integer> Rs = new ArrayList<>();
        ArrayList<Integer> Gs = new ArrayList<>();
        ArrayList<Integer> Bs = new ArrayList<>();

        for (int yy = 0; yy < sub.rows(); yy += step) {
            for (int xx = 0; xx < sub.cols(); xx += step) {
                double[] px = sub.get(yy, xx); // RGBA
                int R = (int)Math.round(px[0]);
                int G = (int)Math.round(px[1]);
                int B = (int)Math.round(px[2]);
                Rs.add(R); Gs.add(G); Bs.add(B);
            }
        }

        sub.release();

        int rMed = medianOf(Rs);
        int gMed = medianOf(Gs);
        int bMed = medianOf(Bs);

        return new Scalar(rMed, gMed, bMed);
    }

    private static int medianOf(ArrayList<Integer> arr) {
        if (arr.isEmpty()) return 0;
        Collections.sort(arr);
        int mid = arr.size() / 2;
        if (arr.size() % 2 == 1) return arr.get(mid);
        return (arr.get(mid - 1) + arr.get(mid)) / 2;
    }

    private static void put(StripSample out, Band band, int idx,
                            Scalar[] hsvMeans, Scalar[] rgbMeds, List<Rect> bands) {
        out.hsvMean.put(band, hsvMeans[idx]);
        out.rgbMedian.put(band, rgbMeds[idx]);
        out.bandRects.put(band, bands.get(idx));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
