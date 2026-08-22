package com.example.patch_detection_camera.processing.detection.strip;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * StripInnerBlockDetector (업그레이드 버전)
 *
 * ✅ 핵심 변경:
 * 1) warped 전체에서 먼저 "strip border"를 margin으로 잘라낸 warpedInner(서브 ROI)에서
 *    - gray/edges/projection/cutsY 계산
 *    - bandRect/innerRect 계산
 * 2) 결과 Rect들은 최종적으로 "원본 warped 좌표계"로 변환해서 반환 (offset 적용)
 * 3) 색 평균은 innerRect ROI + coreMask(erosion) 기반으로 계산(기존 유지)
 *
 * -> 즉, strip의 검은/흰 border를 "처음부터" 잘라내서 band 경계도 더 안정적이고
 *    샘플링도 border가 덜 섞입니다.
 */
public class StripInnerBlockDetector {

    public static class Options {
        // ----------------------- (A) strip 전체 border 제거용 -----------------------
        //  warped에서 먼저 strip 외곽(border/프린트)을 margin으로 제거한 ROI에서만 처리
        public double stripBorderMarginX = 0.18; // 좌우 border 제거 (0.08~0.18 추천)
        public double stripBorderMarginY = 0.02; // 상하 border 제거 (0.04~0.12 추천)

        public double minBandHeightRatio = 0.10; // warpedInner usableH 대비 최소(대략 0.08~0.14)
        public double maxBandHeightRatio = 0.35; // 너무 크면(두 패치 합쳐짐) (0.28~0.40)
        public boolean fallbackToEqualSplitOnBadBands = true;



        // ----------------------- (B) 경계 검출 옵션 -----------------------
        public int blurK = 5;
        public double cannyLo = 40;
        public double cannyHi = 120;

        public int peakMinDistPx = 18;   // warpedInner 높이에 따라 12~30 조정
        public int peakWindow = 9;       // local max window

        // warpedInner 내에서 위/아래 일부 무시(프린트 영향)
        // 비대칭(0.07/0.04)이었을 때 맨 위 밴드(GRAY)가 아래로 심하게 밀리는 문제가 있어 대칭으로 통일
        public double ignoreTopRatio = 0.04;
        public double ignoreBotRatio = 0.04;

        // ----------------------- (C) band 내부 ROI(시각화/샘플링) -----------------------
        public double innerMarginX = 0.20;
        public double innerMarginY = 0.15;

        // ----------------------- (D) 코어 마스크(erosion) -----------------------
        public int coreErodePx = 14;

        public boolean autoCoreErode = true;
        public double autoCoreErodeRatio = 0.12;
        public int autoCoreErodeMin = 3;
        public int autoCoreErodeMax = 30;

        // ----------------------- (E) Debug -----------------------
        public boolean debug = true;
    }

    public static class BandResult {
        public Rect bandRect;     // (원본 warped 좌표계) band 영역
        public Rect innerRect;    // (원본 warped 좌표계) 샘플링 ROI
        public Rect coreRect;     // (원본 warped 좌표계) 참고용 코어 사각형
        public Scalar meanBgr;    // 코어 평균색 (B,G,R)
        public Mat coreMask;      // innerRect ROI 크기의 마스크 (debug일 때만)
    }

    public static class Result {
        public List<BandResult> bands = new ArrayList<>(); // size=5
        public Mat debugEdges;     // optional (warpedInner 기준)
        public Mat debugProjVis;   // optional (warpedInner 기준)
        public List<Integer> cutsY = new ArrayList<>(); // (warpedInner 기준 y) 경계 y들 (size=4)

        public Rect stripInnerRect;
    }

    /** warped strip(Mat: RGBA/BGR 둘 다 가능)에서 5개 band를 검출 + 코어 평균색 계산 */
    public static Result detectBands(Mat warped, Options opt) {
        if (opt == null) opt = new Options();
        Result res = new Result();

        if (warped == null || warped.empty()) return res;

        int WH = warped.rows();
        int WW = warped.cols();

        // ---------------------------------------------------------------------
        // 0) strip border를 먼저 margin으로 제거한 ROI(warpedInner) 생성
        // ---------------------------------------------------------------------
        Rect stripInner = shrinkRect(new Rect(0, 0, WW, WH),
                opt.stripBorderMarginX, opt.stripBorderMarginY, WW, WH);

        // 너무 작아지면 fallback: 원본 사용
        if (stripInner.width < 10 || stripInner.height < 10) {
            stripInner = new Rect(0, 0, WW, WH);
        }
        res.stripInnerRect = stripInner;

        Mat warpedInner = new Mat(warped, stripInner);

        // ---------------------------------------------------------------------
        // 1) Gray + Blur (warpedInner 기준)
        // ---------------------------------------------------------------------
        Mat gray = new Mat();
        if (warpedInner.channels() == 4) Imgproc.cvtColor(warpedInner, gray, Imgproc.COLOR_RGBA2GRAY);
        else if (warpedInner.channels() == 3) Imgproc.cvtColor(warpedInner, gray, Imgproc.COLOR_BGR2GRAY);
        else gray = warpedInner.clone();

        if (opt.blurK >= 3) {
            int k = opt.blurK;
            if (k % 2 == 0) k += 1;
            Imgproc.GaussianBlur(gray, gray, new Size(k, k), 0);
        }

        // ---------------------------------------------------------------------
        // 2) Edge (warpedInner 기준)
        // ---------------------------------------------------------------------
        Mat edges = new Mat();
        Imgproc.Canny(gray, edges, opt.cannyLo, opt.cannyHi);

        // ---------------------------------------------------------------------
        // 3) ignore top/bottom (warpedInner 기준)
        // ---------------------------------------------------------------------
        int H = edges.rows(), W = edges.cols();
        int yTop = (int) Math.round(H * opt.ignoreTopRatio);
        int yBot = (int) Math.round(H * (1.0 - opt.ignoreBotRatio));
        yTop = clamp(yTop, 0, H - 1);
        yBot = clamp(yBot, yTop + 1, H);

        // ---------------------------------------------------------------------
        // 4) projection: 각 y마다 edge count 합 (warpedInner 기준)
        // ---------------------------------------------------------------------
        double[] proj = new double[H];
        for (int y = 0; y < H; y++) proj[y] = 0;

        for (int y = yTop; y < yBot; y++) {
            int count = 0;
            for (int x = 0; x < W; x++) {
                if (edges.get(y, x)[0] > 0) count++;
            }
            proj[y] = count;
        }

        // ---------------------------------------------------------------------
        // 5) local maxima peaks (경계 후보)
        // ---------------------------------------------------------------------
        List<Integer> peaks = findPeaks(proj, yTop, yBot, opt.peakWindow, opt.peakMinDistPx);

        // peaks 중에서 "가장 강한 4개"를 선택하고 y 오름차순 정렬 (색 블록 5개면 경계 4개)
        if (peaks.size() < 4) {
            // fallback: 균등분할
            List<Integer> cuts = new ArrayList<>();
            int usableH = (yBot - yTop);
            for (int i = 1; i <= 4; i++) cuts.add(yTop + (usableH * i) / 5);
            res.cutsY = cuts;
        } else {
            Collections.sort(peaks, (a, b) -> Double.compare(proj[b], proj[a]));
            List<Integer> top4 = peaks.subList(0, 4);
            List<Integer> cuts = new ArrayList<>(top4);
            Collections.sort(cuts);
            res.cutsY = cuts;
        }
        if (opt.fallbackToEqualSplitOnBadBands) {
            int usableH = yBot - yTop;
            int minH = (int)Math.round(usableH * opt.minBandHeightRatio);
            int maxH = (int)Math.round(usableH * opt.maxBandHeightRatio);

            if (!bandsHeightPlausible(res.cutsY, yTop, yBot, minH, maxH)) {
                // fallback: 균등분할
                List<Integer> cuts2 = new ArrayList<>();
                for (int i = 1; i <= 4; i++) cuts2.add(yTop + (usableH * i) / 5);
                res.cutsY = cuts2;
            }
        }

        // ---------------------------------------------------------------------
        // 6) bandRect 생성 + 코어 평균색 계산
        //    ⚠️ band/inner/core는 먼저 warpedInner 좌표계로 만든 뒤,
        //        최종 반환 시 "원본 warped 좌표계"로 offset 적용
        // ---------------------------------------------------------------------
        List<Integer> cuts = res.cutsY;
        int y0 = yTop;

        for (int i = 0; i < 5; i++) {
            int y1 = (i < 4) ? cuts.get(i) : yBot;
            int h = Math.max(1, y1 - y0);

            // (A) warpedInner 좌표계에서 bandRect/innerRect
            Rect bandInner = new Rect(0, y0, W, h);
            Rect innerInner = shrinkRect(bandInner, opt.innerMarginX, opt.innerMarginY, W, H);

            int erodePx = resolveCoreErodePx(opt, innerInner);
            Rect coreInner = shrinkRectPixels(innerInner, erodePx, erodePx, W, H);

            // (B) 원본 warped 좌표계로 offset 적용
            Rect bandOnWarped = offsetRect(bandInner, stripInner.x, stripInner.y, WW, WH);
            Rect innerOnWarped = offsetRect(innerInner, stripInner.x, stripInner.y, WW, WH);
            Rect coreOnWarped = offsetRect(coreInner, stripInner.x, stripInner.y, WW, WH);

            BandResult br = new BandResult();
            br.bandRect = bandOnWarped;
            br.innerRect = innerOnWarped;
            br.coreRect = coreOnWarped;

            // (C) 평균/마스크 계산은 "원본 warped + innerRect(원본 좌표)"로 계산
            CoreMeanResult cm = meanColorCoreUsingInnerRect(warped, br.innerRect, erodePx, opt.debug);
            br.meanBgr = cm.meanBgr;
            br.coreMask = cm.mask; // debug=true이면 innerRect 크기의 마스크

            res.bands.add(br);

            y0 = y1;
        }

        // ---------------------------------------------------------------------
        // 7) debug mats (warpedInner 기준)
        // ---------------------------------------------------------------------
        if (opt.debug) {
            res.debugEdges = edges.clone();
            res.debugProjVis = makeProjectionVis(proj, W, H, yTop, yBot, res.cutsY);
        }

        gray.release();
        edges.release();
        warpedInner.release();

        return res;
    }

    // --------------------- "코어 평균색" 계산 ---------------------

    private static class CoreMeanResult {
        Scalar meanBgr;
        Mat mask; // ROI 크기 (innerRect.size), debug=false면 null
    }

    /**
     * innerRect ROI 안에서 마스크를 만들고, erode로 테두리 제거 후 평균색 계산
     * - warped: RGBA/BGR 가능
     * - innerRect: 원본 warped 좌표계
     */
    private static CoreMeanResult meanColorCoreUsingInnerRect(Mat warped, Rect innerRect, int erodePx, boolean debug) {
        CoreMeanResult out = new CoreMeanResult();
        out.meanBgr = new Scalar(0, 0, 0, 0);
        out.mask = null;

        if (warped == null || warped.empty()) return out;

        Rect r = clampRect(innerRect, warped.cols(), warped.rows());
        Mat roi = new Mat(warped, r);

        // BGR로 통일
        Mat bgr = new Mat();
        boolean needReleaseBgr = true;
        if (roi.channels() == 4) {
            Imgproc.cvtColor(roi, bgr, Imgproc.COLOR_RGBA2BGR);
        } else if (roi.channels() == 3) {
            bgr = roi;
            needReleaseBgr = false;
        } else {
            Imgproc.cvtColor(roi, bgr, Imgproc.COLOR_GRAY2BGR);
        }

        // mask = 전부 255로 시작
        Mat mask = Mat.ones(bgr.rows(), bgr.cols(), CvType.CV_8UC1);
        Core.multiply(mask, new Scalar(255), mask);

        // erode로 border 제거
        if (erodePx > 0) {
            Mat kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    new Size(2 * erodePx + 1, 2 * erodePx + 1)
            );
            Imgproc.erode(mask, mask, kernel);
            kernel.release();
        }

        // 너무 많이 깎였으면 fallback: erodePx 절반
        int nonZero = Core.countNonZero(mask);
        if (nonZero < Math.max(50, (int) (0.02 * mask.total()))) {
            mask.setTo(new Scalar(255));
            int ep2 = Math.max(1, erodePx / 2);
            Mat kernel2 = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    new Size(2 * ep2 + 1, 2 * ep2 + 1)
            );
            Imgproc.erode(mask, mask, kernel2);
            kernel2.release();
        }

        out.meanBgr = Core.mean(bgr, mask);

        if (debug) out.mask = mask;
        else mask.release();

        if (needReleaseBgr) bgr.release();
        roi.release();

        return out;
    }

    private static int resolveCoreErodePx(Options opt, Rect innerRect) {
        int ep = opt.coreErodePx;
        if (opt.autoCoreErode) {
            int base = (int) Math.round(Math.min(innerRect.width, innerRect.height) * opt.autoCoreErodeRatio);
            base = clamp(base, opt.autoCoreErodeMin, opt.autoCoreErodeMax);
            ep = base;
        }
        return Math.max(0, ep);
    }

    // --------------------- helpers ---------------------

    private static Rect shrinkRect(Rect r, double mx, double my, int W, int H) {
        int dx = (int) Math.round(r.width * mx);
        int dy = (int) Math.round(r.height * my);
        int x = clamp(r.x + dx, 0, W - 1);
        int y = clamp(r.y + dy, 0, H - 1);
        int w = clamp(r.width - 2 * dx, 1, W - x);
        int h = clamp(r.height - 2 * dy, 1, H - y);
        return new Rect(x, y, w, h);
    }

    private static Rect shrinkRectPixels(Rect r, int dx, int dy, int W, int H) {
        int x = clamp(r.x + dx, 0, W - 1);
        int y = clamp(r.y + dy, 0, H - 1);
        int w = clamp(r.width - 2 * dx, 1, W - x);
        int h = clamp(r.height - 2 * dy, 1, H - y);
        return new Rect(x, y, w, h);
    }

    private static Rect offsetRect(Rect r, int ox, int oy, int W, int H) {
        Rect out = new Rect(r.x + ox, r.y + oy, r.width, r.height);
        return clampRect(out, W, H);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Rect clampRect(Rect r, int W, int H) {
        int x = clamp(r.x, 0, W - 1);
        int y = clamp(r.y, 0, H - 1);
        int w = clamp(r.width, 1, W - x);
        int h = clamp(r.height, 1, H - y);
        return new Rect(x, y, w, h);
    }

    /** local maxima 탐색 + min distance non-max suppression */
    private static List<Integer> findPeaks(double[] proj, int yTop, int yBot, int win, int minDist) {
        List<Integer> cand = new ArrayList<>();
        int H = proj.length;
        int w = Math.max(3, win);
        if (w % 2 == 0) w += 1;
        int half = w / 2;

        for (int y = Math.max(yTop + half, 0); y < Math.min(yBot - half, H); y++) {
            double v = proj[y];
            if (v <= 0) continue;
            boolean isMax = true;
            for (int k = -half; k <= half; k++) {
                if (proj[y + k] > v) { isMax = false; break; }
            }
            if (isMax) cand.add(y);
        }

        Collections.sort(cand, (a, b) -> Double.compare(proj[b], proj[a]));
        List<Integer> peaks = new ArrayList<>();
        for (int y : cand) {
            boolean ok = true;
            for (int p : peaks) {
                if (Math.abs(p - y) < minDist) { ok = false; break; }
            }
            if (ok) peaks.add(y);
        }
        return peaks;
    }

    private static Mat makeProjectionVis(double[] proj, int W, int H, int yTop, int yBot, List<Integer> cuts) {
        Mat vis = Mat.zeros(H, W, CvType.CV_8UC4);

        double max = 1e-9;
        for (int y = yTop; y < yBot; y++) max = Math.max(max, proj[y]);

        for (int y = yTop; y < yBot; y++) {
            int len = (int) Math.round((proj[y] / max) * (W - 1));
            Imgproc.line(vis, new Point(0, y), new Point(len, y), new Scalar(255, 255, 255, 255), 1);
        }

        for (int cy : cuts) {
            Imgproc.line(vis, new Point(0, cy), new Point(W - 1, cy), new Scalar(0, 255, 0, 255), 2);
        }
        return vis;
    }

    private static boolean bandsHeightPlausible(List<Integer> cuts, int yTop, int yBot, int minH, int maxH) {
        if (cuts == null || cuts.size() < 4) return false;

        int prev = yTop;
        for (int i = 0; i < 4; i++) {
            int y = cuts.get(i);
            int h = y - prev;
            if (h < minH || h > maxH) return false;
            prev = y;
        }
        int lastH = yBot - prev;
        if (lastH < minH || lastH > maxH) return false;

        for (int i = 1; i < 4; i++) if (cuts.get(i) <= cuts.get(i-1)) return false;
        return true;
    }
}