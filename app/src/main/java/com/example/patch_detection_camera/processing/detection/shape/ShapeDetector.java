package com.example.patch_detection_camera.processing.detection.shape;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.*;

public class ShapeDetector {

    public enum ShapeType {
        CIRCLE, TRIANGLE, SQUARE, HEXAGON, UNKNOWN
    }

    public static class DetectedShape {
        public ShapeType type;
        public MatOfPoint contour;
        public Rect bbox;
        public RotatedRect rbox;
        public Point center;
        public double area;
        public double score;

        public DetectedShape(ShapeType type, MatOfPoint contour, Rect bbox, RotatedRect rbox,
                             Point center, double area, double score) {
            this.type = type;
            this.contour = contour;
            this.bbox = bbox;
            this.rbox = rbox;
            this.center = center;
            this.area = area;
            this.score = score;
        }
    }
}