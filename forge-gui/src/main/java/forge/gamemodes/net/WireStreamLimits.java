package forge.gamemodes.net;

import forge.util.IHasForgeLog;

import java.io.ObjectInputStream;
import java.lang.reflect.Method;

/**
 * Resource bounds for a single frame arriving on the multiplayer wire.
 *
 * <p>{@link WireClassFilter} says which classes may be named; these bound what
 * a frame may ask the JVM to do while building them, which is not expressible
 * as a class name:
 *
 * <ul>
 *   <li><b>array length</b> — {@code ObjectInputStream} allocates an array from
 *       the length the peer declares <i>before</i> reading any element, so a
 *       21-byte frame declaring {@code int[200000000]} asks for 762 MB. A cap
 *       on bytes read never sees it.</li>
 *   <li><b>references</b> — every object gets a handle, and the handle table
 *       grows with them.</li>
 *   <li><b>depth</b> — nesting is walked recursively, so a deep enough graph
 *       overflows the stack rather than the heap.</li>
 * </ul>
 *
 * <p>The only hook for any of these is JEP 290, which is why it is installed
 * reflectively rather than compiled against: {@code java.io.ObjectInputFilter}
 * arrived in Android at API 33 and {@code forge-gui-android} declares
 * {@code minSdkVersion=26}. Where the API is absent this is a no-op, so mobile
 * keeps today's behaviour instead of failing at class-load. The pattern ends in
 * {@code *}, so it admits every class and bounds resources only — deciding
 * which classes are acceptable stays with {@link WireClassFilter}, where it is
 * portable.
 *
 * <p>The limits are measured, not guessed. Across 39,387 delta exchanges of the
 * stress-gated suite (draft, sealed, and a 100-game delta run) the observed
 * maxima were 3,635 array elements, 31,378 references and depth 15. Depth is a
 * property of the message types and stayed flat all run; the other two grow
 * with board state, and this was a two-player sample where Forge supports up to
 * eight seats, so their ceilings sit well above what was seen here. The bounds
 * below are therefore set orders of magnitude clear of the measurements rather
 * than snugly above them — a false rejection drops a frame mid-game, and the
 * attacks these stop need values far larger still.
 */
final class WireStreamLimits implements IHasForgeLog {

    /** Measured max 3,635. */
    private static final int MAX_ARRAY_LENGTH =
            Integer.getInteger("forge.net.maxWireArrayLength", 1 << 20);
    /** Measured max 31,378, and it scales with board size and seat count. */
    private static final int MAX_REFERENCES =
            Integer.getInteger("forge.net.maxWireReferences", 1_000_000);
    /** Measured max 15, flat across the run; a stack overflow needs thousands. */
    private static final int MAX_DEPTH =
            Integer.getInteger("forge.net.maxWireDepth", 100);

    private static final Method SET_FILTER = findSetter();
    private static final Object FILTER = createFilter();

    private WireStreamLimits() {
    }

    private static Method findSetter() {
        try {
            return ObjectInputStream.class.getMethod("setObjectInputFilter",
                    Class.forName("java.io.ObjectInputFilter"));
        } catch (final ReflectiveOperationException absent) {
            return null; // Android below API 33, or the iOS downgrader.
        }
    }

    private static Object createFilter() {
        if (SET_FILTER == null) {
            return null;
        }
        try {
            final Class<?> filter = Class.forName("java.io.ObjectInputFilter");
            final Method config = Class.forName("java.io.ObjectInputFilter$Config")
                    .getMethod("createFilter", String.class);
            return filter.cast(config.invoke(null, "maxarray=" + MAX_ARRAY_LENGTH
                    + ";maxrefs=" + MAX_REFERENCES
                    + ";maxdepth=" + MAX_DEPTH
                    + ";*"));
        } catch (final ReflectiveOperationException e) {
            netLog.warn("Wire stream limits unavailable: {}", e.toString());
            return null;
        }
    }

    /** Installs the bounds if this platform has the API; otherwise a no-op. */
    static void applyTo(final ObjectInputStream in) {
        if (FILTER == null) {
            return;
        }
        try {
            SET_FILTER.invoke(in, FILTER);
        } catch (final ReflectiveOperationException e) {
            netLog.warn("Could not install wire stream limits: {}", e.toString());
        }
    }
}
