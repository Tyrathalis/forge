package forge.gamemodes.net;

import forge.util.IHasForgeLog;

import java.io.InvalidClassException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Allowlist for class names arriving on the multiplayer wire.
 *
 * <p>The protocol is Java native serialization, so a peer's frame names the
 * classes to instantiate and {@code ObjectInputStream} obliges. Without a
 * filter the reachable set is the whole classpath — 29,012 classes in a built
 * desktop jar — which is the classic untrusted-deserialization sink.
 *
 * <h2>Why not {@code ObjectInputFilter}</h2>
 *
 * JEP 290 is the textbook answer and the wrong one here.
 * {@code java.io.ObjectInputFilter} arrived in Android at API 33;
 * {@code forge-gui-android} declares {@code minSdkVersion=26}, and
 * {@code forge-gui-ios} runs downgraded bytecode. A
 * {@code setObjectInputFilter} call compiles cleanly for desktop and breaks
 * mobile clients at class-load. A plain name check needs no JDK 9+ API and
 * behaves identically on every target.
 *
 * <p>It is also better placed. Every class instantiated during deserialization
 * funnels through {@link CObjectInputStream}'s {@code readClassDescriptor} /
 * {@code resolveClass}, and there are <b>two</b> streams doing that: the
 * per-frame one in {@link CompatibleObjectDecoder}, and the inner one in
 * {@link TrackableSerializer#unwrapEvents}. A filter installed on the decoder
 * alone would leave the second sink unguarded.
 *
 * <h2>How the allowlist was derived</h2>
 *
 * Empirically, not by guessing. {@code CObjectInputStream} was instrumented to
 * record every resolved name and the headless network harness was run over a
 * lobby, a spell-less game, and three real precon games (constructed and
 * commander): 131 distinct classes, of which 87 are {@code forge.*} and the
 * remaining 44 are JDK collections, boxed primitives, arrays and Guava
 * collections. The prefixes below cover that set with room to spare while
 * excluding every classic gadget library — none of which, as it happens, is on
 * Forge's classpath today either.
 *
 * <p>{@code java.util.**} is admitted wholesale rather than enumerated. Its
 * serializable classes are collections; the ones that appear in published
 * gadget chains ({@code PriorityQueue}, {@code HashMap}) are chain
 * <i>carriers</i> that still need a sink — a {@code Comparator} or
 * {@code InvocationHandler} that executes something — and those live in
 * libraries this filter rejects.
 *
 * <p>{@code java.lang.invoke.SerializedLambda} is admitted because the
 * protocol genuinely carries lambdas: {@code ProtocolMethod.getChoices} takes
 * an {@code FSerializableFunction} and {@code RemoteClientGuiGame} ships it
 * down the wire. This is the one deliberate soft spot. Its
 * {@code readResolve} calls {@code $deserializeLambda$} on a capturing class
 * the peer names, which is narrower than a free gadget chain — the lambda is
 * constructed, not invoked, and its captured arguments are themselves filtered
 * — but it is a real residual. Removing it means changing {@code getChoices}
 * to send pre-rendered strings, a protocol change across desktop and mobile.
 *
 * <h2>Escape hatch</h2>
 *
 * A filter that wrongly rejects legitimate traffic breaks a game mid-match,
 * and the coverage above does not include drafting, sealed, sideboarding or
 * the dialog paths. {@code -Dforge.net.classFilter=off} disables enforcement
 * so a user hitting a false reject can finish their evening; rejections are
 * logged with the class name either way, which is what makes a gap reportable
 * rather than mysterious.
 */
final class WireClassFilter implements IHasForgeLog {

    private static final String ENFORCE_PROPERTY = "forge.net.classFilter";

    /** Package prefixes accepted wholesale. */
    private static final String[] ALLOWED_PREFIXES = {
            "forge.",
            "java.util.",
            "com.google.common.collect.",
    };

    /** Simple names under {@code java.lang} we accept, plus their nested classes. */
    private static final Set<String> ALLOWED_JAVA_LANG = unmodifiableSetOf(
            "Boolean", "Byte", "Character", "Short", "Integer", "Long", "Float", "Double",
            "Number", "String", "Enum", "Object", "Void", "StringBuffer", "StringBuilder");

    /** Fully-qualified names accepted individually. */
    private static final Set<String> ALLOWED_EXACT = unmodifiableSetOf(
            // The protocol ships FSerializableFunction; see class javadoc.
            "java.lang.invoke.SerializedLambda");

    private static final String JAVA_LANG = "java.lang.";
    private static final String PRIMITIVE_DESCRIPTORS = "BCDFIJSZ";

    private WireClassFilter() {
    }

    private static Set<String> unmodifiableSetOf(final String... names) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
    }

    /** Whether the filter refuses disallowed classes, or merely reports them. */
    static boolean isEnforcing() {
        return !"off".equalsIgnoreCase(System.getProperty(ENFORCE_PROPERTY, "on"));
    }

    /**
     * Reject a class the protocol has no business carrying.
     *
     * @throws InvalidClassException before the class is resolved, so a gadget's
     *         static initialiser and constructor never run
     */
    static void checkAllowed(final String rawName) throws InvalidClassException {
        if (isAllowed(rawName)) {
            return;
        }
        if (!isEnforcing()) {
            netLog.warn("Wire class {} is not on the allowlist (filter disabled, allowing)", rawName);
            return;
        }
        netLog.error("Rejected wire class {} — not on the multiplayer allowlist. "
                + "If this is legitimate traffic, please report it; "
                + "-D{}=off disables enforcement.", rawName, ENFORCE_PROPERTY);
        throw new InvalidClassException(rawName, "not permitted by the multiplayer class filter");
    }

    static boolean isAllowed(final String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return false;
        }
        final String type = elementType(rawName);

        // An array of primitives, e.g. "[B" reduces to "B".
        if (type.length() == 1 && PRIMITIVE_DESCRIPTORS.indexOf(type.charAt(0)) >= 0) {
            return true;
        }
        if (ALLOWED_EXACT.contains(type)) {
            return true;
        }
        for (final String prefix : ALLOWED_PREFIXES) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        if (type.startsWith(JAVA_LANG)) {
            String simple = type.substring(JAVA_LANG.length());
            final int nested = simple.indexOf('$');
            if (nested >= 0) {
                simple = simple.substring(0, nested);
            }
            // Reject deeper packages such as java.lang.reflect.* — only the
            // top level of java.lang is in scope here.
            return simple.indexOf('.') < 0 && ALLOWED_JAVA_LANG.contains(simple);
        }
        return false;
    }

    /**
     * Reduce an array name to the type it is an array of.
     *
     * <p>{@code ObjectStreamClass.getName()} uses JVM descriptors for arrays,
     * so {@code "[[Ljava.lang.Object;"} must be checked as
     * {@code java.lang.Object} rather than treated as an unknown name — and,
     * more to the point, an array of a forbidden class must not slip through
     * because its raw name does not match any prefix.
     */
    static String elementType(final String name) {
        int depth = 0;
        while (depth < name.length() && name.charAt(depth) == '[') {
            depth++;
        }
        if (depth == 0) {
            return name;
        }
        final String rest = name.substring(depth);
        if (rest.length() > 2 && rest.charAt(0) == 'L' && rest.endsWith(";")) {
            return rest.substring(1, rest.length() - 1);
        }
        return rest;
    }
}
