package forge.gamemodes.net;

import forge.util.IHasForgeLog;

import java.io.ObjectInputStream;
import java.lang.reflect.Method;

/**
 * Caps the length of arrays declared on the multiplayer wire.
 *
 * <p>{@link WireClassFilter} cannot do this. An array length is data, not a
 * class name, and {@code ObjectInputStream} allocates the array from the
 * length the peer declared <b>before</b> reading a single element — so a
 * 21-byte frame declaring {@code int[200000000]} asks for 762 MB, and a cap on
 * bytes read never sees it either.
 *
 * <p>The only hook for this is JEP 290's {@code ObjectInputFilter}, which is
 * unavailable at {@code minSdkVersion=26} and on the iOS downgrader path. It
 * is therefore installed reflectively: desktop gets the bound, mobile keeps
 * today's behaviour rather than failing at class-load.
 */
final class WireArrayLimit implements IHasForgeLog {

    /** Real traffic peaks in the low thousands; a whole game is ~250 KB. */
    private static final int MAX_ARRAY_LENGTH =
            Integer.getInteger("forge.net.maxWireArrayLength", 1 << 20);

    private static final Method SET_FILTER = findSetter();
    private static final Object FILTER = createFilter();

    private WireArrayLimit() {
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
            return filter.cast(config.invoke(null, "maxarray=" + MAX_ARRAY_LENGTH + ";*"));
        } catch (final ReflectiveOperationException e) {
            netLog.warn("Wire array limit unavailable: {}", e.toString());
            return null;
        }
    }

    /** Installs the bound if this platform has the API; otherwise a no-op. */
    static void applyTo(final ObjectInputStream in) {
        if (FILTER == null) {
            return;
        }
        try {
            SET_FILTER.invoke(in, FILTER);
        } catch (final ReflectiveOperationException e) {
            netLog.warn("Could not install wire array limit: {}", e.toString());
        }
    }
}
