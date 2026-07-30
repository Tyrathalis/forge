package forge.util;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins {@link RotatedRect} to the historical tapped-card geometry.
 *
 * The mobile client's pre-helper hit-test swapped w/h and shifted the top by
 * h - w, assuming exactly 90 degrees of tap rotation. The helper must
 * reproduce that box bit-for-bit at the stock angle (-90 in the
 * startRotateTransform convention) — that identity is the gate on the
 * refactor — while behaving like the actual draw transform at every other
 * angle, which the legacy math cannot.
 */
public class RotatedRectTest {

    // Panel geometries: padding, then card w/h. First row is chosen so every
    // boundary lands on exactly representable floats; the rest are awkward
    // sizes from real layouts (h = visibleHeight, w = h / 1.4 aspect).
    private static final float[][] GEOMETRIES = {
            { 2f, 100f, 140f },
            { 2f, 102.383f, 143.336f },
            { 3.7f, 61.55f, 86.17f },
            { 2f, 297.5f, 416.5f },
    };

    /** The pre-helper hit-test box: w/h swapped, top shifted by h - w. */
    private static boolean legacyTappedContains(float x, float y, float left, float top, float w, float h) {
        top += h - w;
        float temp = w;
        w = h;
        h = temp;
        return x >= left && x <= left + w && y >= top && y <= top + h;
    }

    /** The draw transform, double precision: card-local point to screen. */
    private static double[] forward(double lx, double ly, double px, double py, double angleDeg) {
        double rad = Math.toRadians(angleDeg);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double dx = lx - px, dy = ly - py;
        return new double[] { px + dx * cos + dy * sin, py - dx * sin + dy * cos };
    }

    private static float tapPivotX(float left, float w) {
        return left + w / 2;
    }

    private static float tapPivotY(float top, float h, float w) {
        return top + h - w / 2;
    }

    @Test
    public void identicalToLegacySwapBoxAtStockAngle() {
        for (float[] geom : GEOMETRIES) {
            float left = geom[0], top = geom[0], w = geom[1], h = geom[2];
            float span = 2 * h;
            int mismatches = 0;
            float firstX = 0, firstY = 0;
            for (float x = -span; x <= span; x += 0.25f) {
                for (float y = -span; y <= span; y += 0.25f) {
                    boolean legacy = legacyTappedContains(x, y, left, top, w, h);
                    boolean actual = RotatedRect.contains(x, y, left, top, w, h,
                            tapPivotX(left, w), tapPivotY(top, h, w), -90);
                    if (legacy != actual) {
                        if (mismatches == 0) {
                            firstX = x;
                            firstY = y;
                        }
                        mismatches++;
                    }
                }
            }
            Assert.assertEquals(mismatches, 0, "legacy/helper disagreement for geometry (" + geom[0] + ", " + w
                    + ", " + h + "), first at (" + firstX + ", " + firstY + ")");
        }
    }

    @Test
    public void matchesDrawTransformAtArbitraryAngles() {
        float[] angles = { -90, 90, -60, 60, -45, 37.3f, 180, 0 };
        for (float[] geom : GEOMETRIES) {
            float left = geom[0], top = geom[0], w = geom[1], h = geom[2];
            float px = tapPivotX(left, w), py = tapPivotY(top, h, w);
            for (float angle : angles) {
                // Lattice of card-local points with a margin either side of the
                // edges; forward-map each to screen and demand contains() agree
                // with local membership.
                float margin = 2f;
                for (float lx = left - 3 * margin; lx <= left + w + 3 * margin; lx += 1.7f) {
                    for (float ly = top - 3 * margin; ly <= top + h + 3 * margin; ly += 1.7f) {
                        boolean inside = lx >= left + margin && lx <= left + w - margin
                                && ly >= top + margin && ly <= top + h - margin;
                        boolean outside = lx < left - margin || lx > left + w + margin
                                || ly < top - margin || ly > top + h + margin;
                        if (!inside && !outside) {
                            continue; // skip the boundary band
                        }
                        double[] s = forward(lx, ly, px, py, angle);
                        boolean actual = RotatedRect.contains((float) s[0], (float) s[1],
                                left, top, w, h, px, py, angle);
                        Assert.assertEquals(actual, inside, "angle " + angle + ", local (" + lx + ", " + ly
                                + ") -> screen (" + s[0] + ", " + s[1] + ")");
                    }
                }
            }
        }
    }

    /**
     * The box agrees with the legacy swap box to ~1e-5 (float rounding of the
     * pivot at the call site), which is far below a pixel; bit-exactness is
     * asserted only for the hit-test, where a flipped comparison could matter.
     */
    @Test
    public void boundingBoxIsLegacySwapBoxAtStockAngle() {
        for (float[] geom : GEOMETRIES) {
            float left = geom[0], top = geom[0], w = geom[1], h = geom[2];
            float[] bb = RotatedRect.boundingBox(left, top, w, h,
                    tapPivotX(left, w), tapPivotY(top, h, w), -90);
            Assert.assertEquals(bb[0], left, 1e-4f, "bb x");
            Assert.assertEquals(bb[1], top + h - w, 1e-4f, "bb y");
            Assert.assertEquals(bb[2], h, 1e-4f, "bb w");
            Assert.assertEquals(bb[3], w, 1e-4f, "bb h");
        }
    }

    @Test
    public void boundingBoxMatchesForwardMappedCorners() {
        float[] angles = { -90, 90, -60, 60, 25f, 180, 0 };
        for (float[] geom : GEOMETRIES) {
            float left = geom[0], top = geom[0], w = geom[1], h = geom[2];
            float px = tapPivotX(left, w), py = tapPivotY(top, h, w);
            for (float angle : angles) {
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                for (int corner = 0; corner < 4; corner++) {
                    double[] s = forward((corner & 1) == 0 ? left : left + w,
                            (corner & 2) == 0 ? top : top + h, px, py, angle);
                    minX = Math.min(minX, s[0]);
                    minY = Math.min(minY, s[1]);
                    maxX = Math.max(maxX, s[0]);
                    maxY = Math.max(maxY, s[1]);
                }
                float[] bb = RotatedRect.boundingBox(left, top, w, h, px, py, angle);
                Assert.assertEquals(bb[0], (float) minX, 1e-3f, "bb x at " + angle);
                Assert.assertEquals(bb[1], (float) minY, 1e-3f, "bb y at " + angle);
                Assert.assertEquals(bb[2], (float) (maxX - minX), 1e-3f, "bb w at " + angle);
                Assert.assertEquals(bb[3], (float) (maxY - minY), 1e-3f, "bb h at " + angle);
            }
        }
    }

    /**
     * The desktop form: PlayArea.getCardPanel uses integer pixel coordinates
     * and EXCLUSIVE bounds. Its legacy tapped branch swapped w/h and shifted y
     * by h - w; the replacement inverse-rotates via
     * {@link RotatedRect#inverseRotate} and keeps the exclusive comparisons.
     * Must be identical over integer grids at the stock angle.
     */
    @Test
    public void desktopExclusiveFormIdenticalToLegacyAtStockAngle() {
        int[][] panels = { { 12, 30, 100, 140 }, { 0, 0, 63, 89 }, { 7, 3, 101, 141 } };
        for (int[] p : panels) {
            int panelX = p[0], panelY = p[1], w = p[2], h = p[3];
            int mismatches = 0;
            for (int x = panelX - h - 10; x <= panelX + 2 * h; x++) {
                for (int y = panelY - h - 10; y <= panelY + 2 * h; y++) {
                    // legacy: swap + shift, exclusive bounds
                    int ly = panelY + h - w;
                    boolean legacy = x > panelX && x < panelX + h && y > ly && y < ly + w;
                    // replacement: inverse-rotate about the paint() pivot, exclusive bounds
                    float[] local = RotatedRect.inverseRotate(x, y, panelX + w / 2f, panelY + h - w / 2f, -90);
                    boolean actual = local[0] > panelX && local[0] < panelX + w
                            && local[1] > panelY && local[1] < panelY + h;
                    if (legacy != actual) {
                        mismatches++;
                    }
                }
            }
            Assert.assertEquals(mismatches, 0, "desktop-form disagreement for panel (" + panelX + ", " + panelY
                    + ", " + w + ", " + h + ")");
        }
    }

    /**
     * The 180-rotated-field composition (local two-human matches): the card
     * draws under an outer 180-degree rotation about the untapped rect center,
     * then the +90 tap rotation. The hit-test undoes the outer rotation by
     * reflecting the query point about the center, then runs the plain +90
     * test. This must agree with membership under the full composed forward
     * transform.
     */
    @Test
    public void rotate180CompositionMatchesComposedDraw() {
        for (float[] geom : GEOMETRIES) {
            float left = geom[0], top = geom[0], w = geom[1], h = geom[2];
            float px = tapPivotX(left, w), py = tapPivotY(top, h, w);
            float cx = left + w / 2, cy = top + h / 2;
            float margin = 2f;
            for (float lx = left - 3 * margin; lx <= left + w + 3 * margin; lx += 1.7f) {
                for (float ly = top - 3 * margin; ly <= top + h + 3 * margin; ly += 1.7f) {
                    boolean inside = lx >= left + margin && lx <= left + w - margin
                            && ly >= top + margin && ly <= top + h - margin;
                    boolean outside = lx < left - margin || lx > left + w + margin
                            || ly < top - margin || ly > top + h + margin;
                    if (!inside && !outside) {
                        continue;
                    }
                    double[] inner = forward(lx, ly, px, py, 90);
                    double[] s = forward(inner[0], inner[1], cx, cy, 180);
                    // the CardAreaPanel override: reflect about the center, then base test at +90
                    float qx = 2 * cx - (float) s[0];
                    float qy = 2 * cy - (float) s[1];
                    boolean actual = RotatedRect.contains(qx, qy, left, top, w, h, px, py, 90);
                    Assert.assertEquals(actual, inside, "composed local (" + lx + ", " + ly + ")");
                }
            }
        }
    }
}
