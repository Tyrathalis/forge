package forge.util;

/**
 * Hit-testing and bounds math for rectangles drawn under a canvas rotation.
 *
 * Both clients draw tapped cards by rotating the canvas about a pivot and then
 * drawing the plain unrotated card rect; input coordinates never see draw
 * transforms. So a hit-test must inverse-rotate the query point into the
 * card's unrotated frame instead of assuming the drawn footprint is the
 * axis-aligned 90-degree w/h swap.
 *
 * Angle convention: degrees, as passed to the mobile client's
 * {@code Graphics.startRotateTransform} (positive = counter-clockwise on a
 * y-down screen). Forge's tapped cards are -90 in this convention. Swing
 * callers rotate the opposite way round: pass the negation of the
 * {@code Graphics2D.rotate} angle converted to degrees.
 *
 * Trig is snapped exactly at multiples of 90 degrees so that, at the stock tap
 * angle, results reproduce the historical w/h-swap hit boxes bit-for-bit.
 */
public final class RotatedRect {
    private RotatedRect() {
    }

    /**
     * True if the screen-space point (px, py) lands on the rect
     * (rx, ry, rw, rh) as drawn rotated by angleDeg about (pivotX, pivotY).
     * Bounds are inclusive, matching the historical tapped-card hit boxes.
     */
    public static boolean contains(float px, float py, float rx, float ry, float rw, float rh,
            float pivotX, float pivotY, float angleDeg) {
        double cos = cosDeg(angleDeg);
        double sin = sinDeg(angleDeg);
        double dx = px - (double) pivotX;
        double dy = py - (double) pivotY;
        // The draw transform maps card-local d to screen (d.x*cos + d.y*sin, -d.x*sin + d.y*cos);
        // this is its inverse.
        double lx = pivotX + dx * cos - dy * sin;
        double ly = pivotY + dx * sin + dy * cos;
        return lx >= rx && lx <= rx + (double) rw && ly >= ry && ly <= ry + (double) rh;
    }

    /**
     * Axis-aligned bounding box, as {x, y, w, h}, of the rect
     * (rx, ry, rw, rh) drawn rotated by angleDeg about (pivotX, pivotY).
     * At -90 this is exactly the historical tapped w/h-swap box.
     */
    public static float[] boundingBox(float rx, float ry, float rw, float rh,
            float pivotX, float pivotY, float angleDeg) {
        double cos = cosDeg(angleDeg);
        double sin = sinDeg(angleDeg);
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            double dx = ((corner & 1) == 0 ? rx : rx + (double) rw) - pivotX;
            double dy = ((corner & 2) == 0 ? ry : ry + (double) rh) - pivotY;
            double sx = pivotX + dx * cos + dy * sin;
            double sy = pivotY - dx * sin + dy * cos;
            minX = Math.min(minX, sx);
            minY = Math.min(minY, sy);
            maxX = Math.max(maxX, sx);
            maxY = Math.max(maxY, sy);
        }
        return new float[] { (float) minX, (float) minY, (float) (maxX - minX), (float) (maxY - minY) };
    }

    private static double cosDeg(float angleDeg) {
        float a = normalize(angleDeg);
        if (a == 0) { return 1; }
        if (a == 90 || a == 270) { return 0; }
        if (a == 180) { return -1; }
        return Math.cos(Math.toRadians(a));
    }

    private static double sinDeg(float angleDeg) {
        float a = normalize(angleDeg);
        if (a == 0 || a == 180) { return 0; }
        if (a == 90) { return 1; }
        if (a == 270) { return -1; }
        return Math.sin(Math.toRadians(a));
    }

    private static float normalize(float angleDeg) {
        float a = angleDeg % 360;
        return a < 0 ? a + 360 : a;
    }
}
