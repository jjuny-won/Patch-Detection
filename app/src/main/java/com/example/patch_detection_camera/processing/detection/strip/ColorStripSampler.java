package com.example.patch_detection_camera.processing.detection.strip;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.*;

public class ColorStripSampler {

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

        // 2) warped에서 내부 margin 제외한 5개 band Rect 생성
        List<Rect> bands = makeFiveVerticalBands(warped.cols(), warped.rows(), 0.10);

        // 3) 각 band의 HSV mean + RGB median 계산
        Scalar[] hsvMeans = new Scalar[5];
        Scalar[] rgbMeds  = new Scalar[5];

        for (int i = 0; i < 5; i++) {
            Rect br = bands.get(i);

            hsvMeans[i] = meanHSV(warped, br);
            rgbMeds[i]  = medianRGB(warped, br); // RGB 순서 Scalar(r,g,b)
        }

        // 4) 자동 라벨링: White(최대 V), Gray(최소 S, white 제외), 나머지 3개는 Hue로 R/G/B
        int whiteIdx = argmaxV(hsvMeans);
        int grayIdx  = argminSExcept(hsvMeans, whiteIdx);

        List<Integer> rest = new ArrayList<>();
        for (int i = 0; i < 5; i++) if (i != whiteIdx && i != grayIdx) rest.add(i);

        int rIdx = -1, gIdx = -1, bIdx = -1;
        for (int idx : rest) {
            double h = hsvMeans[idx].val[0]; // 0~180
            double dR = Math.min(Math.abs(h - 0), Math.abs(h - 180));
            double dG = Math.abs(h - 60);
            double dB = Math.abs(h - 120);

            if (dR <= dG && dR <= dB) rIdx = idx;
            else if (dG <= dR && dG <= dB) gIdx = idx;
            else bIdx = idx;
        }

        // 혹시 예외적으로 하나가 못 잡히면(색이 흐리거나), hue 기준으로 남은 것 재배치
        if (rIdx < 0 || gIdx < 0 || bIdx < 0) {
            int[] fallback = assignRGBByBestMatch(hsvMeans, rest);
            rIdx = fallback[0]; gIdx = fallback[1]; bIdx = fallback[2];
        }

        // 5) 결과 맵에 담기
        put(out, Band.WHITE, whiteIdx, hsvMeans, rgbMeds, bands);
        put(out, Band.GRAY,  grayIdx,  hsvMeans, rgbMeds, bands);
        put(out, Band.RED,   rIdx,     hsvMeans, rgbMeds, bands);
        put(out, Band.GREEN, gIdx,     hsvMeans, rgbMeds, bands);
        put(out, Band.BLUE,  bIdx,     hsvMeans, rgbMeds, bands);

        return out;
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

    // -------------------------
    // Labeling helpers
    // -------------------------
    private static int argmaxV(Scalar[] hsvMeans) {
        int best = 0;
        double bestV = hsvMeans[0].val[2];
        for (int i = 1; i < hsvMeans.length; i++) {
            if (hsvMeans[i].val[2] > bestV) { bestV = hsvMeans[i].val[2]; best = i; }
        }
        return best;
    }

    private static int argminSExcept(Scalar[] hsvMeans, int exceptIdx) {
        int best = -1;
        double bestS = 1e18;
        for (int i = 0; i < hsvMeans.length; i++) {
            if (i == exceptIdx) continue;
            if (hsvMeans[i].val[1] < bestS) { bestS = hsvMeans[i].val[1]; best = i; }
        }
        return (best >= 0) ? best : 0;
    }

    // 혹시 R/G/B가 하나라도 못 잡히면, 3개 후보를 “최소 거리 매칭”으로 강제 할당
    // return: [rIdx, gIdx, bIdx]
    private static int[] assignRGBByBestMatch(Scalar[] hsvMeans, List<Integer> idxs) {
        // 목표 hue: R=0(또는180), G=60, B=120
        // 3개 후보에 대해 모든 permutation 비용 계산
        int n = idxs.size();
        if (n != 3) return new int[]{ idxs.get(0), idxs.get(Math.min(1,n-1)), idxs.get(Math.min(2,n-1)) };

        int a = idxs.get(0), b = idxs.get(1), c = idxs.get(2);
        int[][] perms = new int[][]{
                {a,b,c},{a,c,b},{b,a,c},{b,c,a},{c,a,b},{c,b,a}
        };

        double bestCost = 1e18;
        int[] best = perms[0];

        for (int[] p : perms) {
            double cost =
                    hueDistToRed(hsvMeans[p[0]].val[0]) +
                            Math.abs(hsvMeans[p[1]].val[0] - 60) +
                            Math.abs(hsvMeans[p[2]].val[0] - 120);
            if (cost < bestCost) { bestCost = cost; best = p; }
        }
        return new int[]{ best[0], best[1], best[2] };
    }

    private static double hueDistToRed(double h) {
        return Math.min(Math.abs(h - 0), Math.abs(h - 180));
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
