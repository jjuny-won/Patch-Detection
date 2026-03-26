package com.example.patch_detection_camera.processing.detection.shape;

import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ShapeDetectionPostProcessor {

    private static final String TAG = "ShapePost";

    public static class Options {
        public boolean debug = true;

        // 최종적으로 유지할 타입들
        public Set<ShapeDetector.ShapeType> targetTypes = EnumSet.of(
                ShapeDetector.ShapeType.CIRCLE,
                ShapeDetector.ShapeType.TRIANGLE,
                ShapeDetector.ShapeType.SQUARE,
                ShapeDetector.ShapeType.HEXAGON
        );

        // 아주 약한 후보 제거
        public double minScore = 0.75;

        // disk 기준 최소 면적
        public double minAreaRatioOfDisk = 0.0035;

        // 전역 NMS
        public boolean enableGlobalNms = true;
        public double nmsIouThr = 0.28;

        // 같은 중심에 매우 가까운 후보 제거
        public boolean enableCenterDistanceSuppression = true;
        public double centerDistRatioOfMinBox = 0.45;

        // 타입별 1개만 유지
        public boolean keepBestPerType = true;

        // strip과 많이 겹치면 제거하고 싶을 때
        public boolean rejectStripOverlap = false;
        public double stripOverlapIouThr = 0.25;
    }

    public static List<ShapeDetector.DetectedShape> selectFinalShapes(
            List<ShapeDetector.DetectedShape> candidates,
            Mat diskMask,
            RotatedRect stripOrNull,
            Options opt
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        if (opt == null) opt = new Options();

        double diskArea = 1.0;
        if (diskMask != null && !diskMask.empty()) {
            diskArea = Math.max(1.0, org.opencv.core.Core.countNonZero(diskMask));
        }
        double minArea = diskArea * opt.minAreaRatioOfDisk;

        List<ShapeDetector.DetectedShape> filtered = new ArrayList<>();

        for (ShapeDetector.DetectedShape s : candidates) {
            if (s == null) continue;
            if (s.type == null || s.type == ShapeDetector.ShapeType.UNKNOWN) {
                releaseShape(s);
                continue;
            }
            if (!opt.targetTypes.contains(s.type)) {
                releaseShape(s);
                continue;
            }
            if (s.score < opt.minScore) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "REJECT LOW SCORE type=%s score=%.3f",
                            s.type.name(), s.score));
                }
                releaseShape(s);
                continue;
            }
            if (s.area < minArea) {
                if (opt.debug) {
                    Log.d(TAG, String.format(Locale.US,
                            "REJECT SMALL AREA type=%s area=%.1f minArea=%.1f",
                            s.type.name(), s.area, minArea));
                }
                releaseShape(s);
                continue;
            }
            if (s.bbox == null || s.bbox.width <= 0 || s.bbox.height <= 0) {
                releaseShape(s);
                continue;
            }

            if (opt.rejectStripOverlap && stripOrNull != null) {
                Rect stripBox = stripOrNull.boundingRect();
                double iou = iou(s.bbox, stripBox);
                if (iou > opt.stripOverlapIouThr) {
                    if (opt.debug) {
                        Log.d(TAG, String.format(Locale.US,
                                "REJECT STRIP OVERLAP type=%s iou=%.3f",
                                s.type.name(), iou));
                    }
                    releaseShape(s);
                    continue;
                }
            }

            filtered.add(s);
        }

        if (filtered.isEmpty()) {
            return filtered;
        }

        // score 내림차순
        filtered.sort(new Comparator<ShapeDetector.DetectedShape>() {
            @Override
            public int compare(ShapeDetector.DetectedShape a, ShapeDetector.DetectedShape b) {
                return Double.compare(b.score, a.score);
            }
        });

        List<ShapeDetector.DetectedShape> suppressed = filtered;

        if (opt.enableGlobalNms || opt.enableCenterDistanceSuppression) {
            suppressed = suppressOverlaps(filtered, opt);
        }

        if (!opt.keepBestPerType) {
            return suppressed;
        }

        EnumMap<ShapeDetector.ShapeType, ShapeDetector.DetectedShape> bestMap =
                new EnumMap<>(ShapeDetector.ShapeType.class);

        for (ShapeDetector.DetectedShape s : suppressed) {
            ShapeDetector.DetectedShape cur = bestMap.get(s.type);
            if (cur == null) {
                bestMap.put(s.type, s);
            } else if (s.score > cur.score) {
                releaseShape(cur);
                bestMap.put(s.type, s);
            } else {
                releaseShape(s);
            }
        }

        List<ShapeDetector.DetectedShape> out = new ArrayList<>();
        for (ShapeDetector.ShapeType t : opt.targetTypes) {
            ShapeDetector.DetectedShape s = bestMap.get(t);
            if (s != null) out.add(s);
        }

        if (opt.debug) {
            for (ShapeDetector.DetectedShape s : out) {
                Log.d(TAG, String.format(Locale.US,
                        "FINAL type=%s bbox=%s area=%.1f score=%.3f",
                        s.type.name(),
                        s.bbox.toString(),
                        s.area,
                        s.score));
            }
        }

        return out;
    }

    private static List<ShapeDetector.DetectedShape> suppressOverlaps(
            List<ShapeDetector.DetectedShape> sorted,
            Options opt
    ) {
        List<ShapeDetector.DetectedShape> keep = new ArrayList<>();

        for (ShapeDetector.DetectedShape cand : sorted) {
            boolean suppressed = false;

            for (ShapeDetector.DetectedShape kept : keep) {
                if (kept == null || kept.bbox == null || cand.bbox == null) continue;

                boolean overlapHit = false;

                if (opt.enableGlobalNms) {
                    double ov = iou(cand.bbox, kept.bbox);
                    if (ov > opt.nmsIouThr) {
                        overlapHit = true;
                        if (opt.debug) {
                            Log.d(TAG, String.format(Locale.US,
                                    "SUPPRESS IOU cand=%s kept=%s ov=%.3f candScore=%.3f keptScore=%.3f",
                                    cand.type.name(), kept.type.name(), ov, cand.score, kept.score));
                        }
                    }
                }

                if (!overlapHit && opt.enableCenterDistanceSuppression) {
                    double d = centerDistance(cand.bbox, kept.bbox);
                    double ref = Math.max(1.0, Math.min(
                            Math.min(cand.bbox.width, cand.bbox.height),
                            Math.min(kept.bbox.width, kept.bbox.height)
                    ));
                    double thr = ref * opt.centerDistRatioOfMinBox;
                    if (d < thr) {
                        overlapHit = true;
                        if (opt.debug) {
                            Log.d(TAG, String.format(Locale.US,
                                    "SUPPRESS CENTER cand=%s kept=%s dist=%.2f thr=%.2f candScore=%.3f keptScore=%.3f",
                                    cand.type.name(), kept.type.name(), d, thr, cand.score, kept.score));
                        }
                    }
                }

                if (overlapHit) {
                    suppressed = true;
                    break;
                }
            }

            if (suppressed) {
                releaseShape(cand);
            } else {
                keep.add(cand);
            }
        }

        return keep;
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

    private static double centerDistance(Rect a, Rect b) {
        double ax = a.x + a.width * 0.5;
        double ay = a.y + a.height * 0.5;
        double bx = b.x + b.width * 0.5;
        double by = b.y + b.height * 0.5;
        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static void releaseShape(ShapeDetector.DetectedShape s) {
        if (s == null) return;
        if (s.contour != null) {
            try {
                s.contour.release();
            } catch (Exception ignore) {}
        }
    }
}