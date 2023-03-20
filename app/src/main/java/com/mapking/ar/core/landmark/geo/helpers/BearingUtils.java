package com.mapking.ar.core.landmark.geo.helpers;

import androidx.annotation.FloatRange;
import androidx.annotation.IntRange;

public class BearingUtils {


    private static Quarternion bearingToQuaternion(@FloatRange(from = 0, to = 360) float bearing) {
        if (bearing < 0) {
            throw new IllegalArgumentException("Bearing must be between 0 and 360");
        }
        if (bearing > 90 && bearing < 270) {
            float qx = (-bearing + 180f);
            float qy = (-bearing + 180f);
            float qz = (-bearing + 180f);
            float qw = (-bearing + 180f);
            return new Quarternion(qx, qy, qz, qw);
        }
        return new Quarternion(0f, 0f, 0f, 0f);
    }

    public static double getHeadingFromBearing(@FloatRange(from = 0, to = 360) float bearing) {
        if (bearing < 0 || bearing > 360) {
            throw new IllegalArgumentException("Bearing must be between 0 and 360");
        }
        if (bearing > 180) {
            return -180 + (bearing -180);
        }
        return bearing;
    }

    public static float getBearingWithSubtract(@FloatRange(from = 0, to = 360) float bearing, @FloatRange(from = 0, to = 360) float subtract) {
        if (bearing < 0 || bearing > 360 || subtract < 0 || subtract > 360) {
            throw new IllegalArgumentException("Bearing must be between 0 and 360");
        }

        if (bearing - subtract < 0) {
            return 360 + (bearing - subtract);
        }
        return bearing - subtract;
    }

    public static class Quarternion {
        public final float qx;
        public final float qy;
        public final float qz;
        public final float qw;

        public Quarternion(float qx, float qy, float qz, float qw) {
            this.qx = qx;
            this.qy = qy;
            this.qz = qz;
            this.qw = qw;
        }
    }
}
