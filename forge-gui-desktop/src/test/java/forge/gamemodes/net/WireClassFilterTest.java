package forge.gamemodes.net;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * F-01: the multiplayer wire must name only classes the protocol carries.
 *
 * <p>The allowlist was derived empirically — {@code CObjectInputStream} was
 * instrumented and the headless network harness run over a lobby, a spell-less
 * game and three real precon games, yielding 131 distinct classes. These tests
 * pin both directions: the measured traffic keeps working, and the shapes an
 * attacker reaches for do not.
 */
public class WireClassFilterTest {

    // ------------------------------------------------------------------
    // Measured traffic must keep working
    // ------------------------------------------------------------------

    @Test
    public void testAllowsForgeProtocolClasses() {
        Assert.assertTrue(WireClassFilter.isAllowed("forge.game.card.CardView"));
        Assert.assertTrue(WireClassFilter.isAllowed("forge.gamemodes.net.event.LoginEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed("forge.trackable.TrackableProperty"));
        Assert.assertTrue(WireClassFilter.isAllowed("forge.item.PaperCard$PaperCardFlags"));
    }

    @Test
    public void testAllowsTheJdkCollectionsSeenOnTheWire() {
        for (final String name : new String[] {
                "java.util.ArrayList", "java.util.HashMap", "java.util.HashSet",
                "java.util.LinkedHashMap", "java.util.TreeSet", "java.util.EnumMap",
                "java.util.EnumSet$SerializationProxy",
                "java.util.Collections$UnmodifiableSet",
                "java.util.concurrent.ConcurrentHashMap",
                "java.util.concurrent.ConcurrentHashMap$Segment",
                "java.util.concurrent.locks.ReentrantLock$NonfairSync" }) {
            Assert.assertTrue(WireClassFilter.isAllowed(name), name + " was measured on the wire");
        }
    }

    @Test
    public void testAllowsBoxedPrimitivesAndNestedJavaLang() {
        Assert.assertTrue(WireClassFilter.isAllowed("java.lang.Integer"));
        Assert.assertTrue(WireClassFilter.isAllowed("java.lang.Boolean"));
        Assert.assertTrue(WireClassFilter.isAllowed("java.lang.Number"));
        Assert.assertTrue(WireClassFilter.isAllowed("java.lang.Enum"));
        // Measured: arrives as a comparator inside a TreeSet.
        Assert.assertTrue(WireClassFilter.isAllowed("java.lang.String$CaseInsensitiveComparator"));
    }

    @Test
    public void testAllowsGuavaCollections() {
        Assert.assertTrue(WireClassFilter.isAllowed("com.google.common.collect.HashMultimap"));
        Assert.assertTrue(WireClassFilter.isAllowed("com.google.common.collect.ImmutableSet$SerializedForm"));
    }

    @Test
    public void testAllowsArrayFormsThatWereMeasured() {
        Assert.assertTrue(WireClassFilter.isAllowed("[B"));
        Assert.assertTrue(WireClassFilter.isAllowed("[I"));
        Assert.assertTrue(WireClassFilter.isAllowed("[Ljava.lang.Object;"));
        Assert.assertTrue(WireClassFilter.isAllowed("[Ljava.lang.Enum;"));
        Assert.assertTrue(WireClassFilter.isAllowed("[Ljava.util.concurrent.ConcurrentHashMap$Segment;"));
    }

    /**
     * The protocol really does ship lambdas: {@code ProtocolMethod.getChoices}
     * carries an {@code FSerializableFunction}. This is the filter's one
     * deliberate soft spot, so it is pinned rather than left to drift.
     */
    @Test
    public void testAllowsSerializedLambda() {
        Assert.assertTrue(WireClassFilter.isAllowed("java.lang.invoke.SerializedLambda"));
    }

    // ------------------------------------------------------------------
    // Attack shapes must not
    // ------------------------------------------------------------------

    @Test
    public void testRejectsClassicGadgetLibraries() {
        for (final String name : new String[] {
                "org.apache.commons.collections.functors.InvokerTransformer",
                "org.apache.commons.collections4.functors.InvokerTransformer",
                "org.apache.commons.beanutils.BeanComparator",
                "org.codehaus.groovy.runtime.ConvertedClosure",
                "com.sun.rowset.JdbcRowSetImpl",
                "org.springframework.beans.factory.ObjectFactory",
                "com.mchange.v2.c3p0.PoolBackedDataSource",
                "bsh.Interpreter",
                "com.thoughtworks.xstream.XStream" }) {
            Assert.assertFalse(WireClassFilter.isAllowed(name), name + " must be rejected");
        }
    }

    /**
     * {@code java.lang} is admitted by simple name, not by prefix, so deeper
     * packages under it stay out — {@code java.lang.reflect.Proxy} is the
     * entry point for the dynamic-proxy/InvocationHandler chains.
     */
    @Test
    public void testRejectsDeeperJavaLangPackages() {
        Assert.assertFalse(WireClassFilter.isAllowed("java.lang.reflect.Proxy"));
        Assert.assertFalse(WireClassFilter.isAllowed(
                "java.lang.reflect.annotation.AnnotationInvocationHandler"));
        Assert.assertFalse(WireClassFilter.isAllowed("java.lang.Runtime"));
        Assert.assertFalse(WireClassFilter.isAllowed("java.lang.ProcessBuilder"));
    }

    @Test
    public void testRejectsOtherJdkAndPlatformPackages() {
        Assert.assertFalse(WireClassFilter.isAllowed("javax.management.BadAttributeValueExpException"));
        Assert.assertFalse(WireClassFilter.isAllowed("javax.naming.InitialContext"));
        Assert.assertFalse(WireClassFilter.isAllowed("sun.reflect.annotation.AnnotationInvocationHandler"));
        Assert.assertFalse(WireClassFilter.isAllowed("java.rmi.registry.Registry"));
        Assert.assertFalse(WireClassFilter.isAllowed("java.net.URL"));
    }

    /**
     * The normalization that is easy to get wrong: an array of a forbidden
     * class has a raw name matching no allowed prefix, so a filter that
     * compared raw names would reject it by accident — and one that stripped
     * the decoration carelessly could admit it. Check the decorated form is
     * judged by its element type.
     */
    @Test
    public void testRejectsArraysOfForbiddenClasses() {
        Assert.assertFalse(WireClassFilter.isAllowed(
                "[Lorg.apache.commons.collections.Transformer;"));
        Assert.assertFalse(WireClassFilter.isAllowed(
                "[[Ljavax.management.BadAttributeValueExpException;"));
        Assert.assertFalse(WireClassFilter.isAllowed("[Ljava.lang.reflect.Proxy;"));
    }

    @Test
    public void testElementTypeStripsArrayDecoration() {
        Assert.assertEquals(WireClassFilter.elementType("forge.game.card.CardView"),
                "forge.game.card.CardView");
        Assert.assertEquals(WireClassFilter.elementType("[Ljava.lang.Object;"), "java.lang.Object");
        Assert.assertEquals(WireClassFilter.elementType("[[Ljava.lang.Object;"), "java.lang.Object");
        Assert.assertEquals(WireClassFilter.elementType("[B"), "B");
    }

    @Test
    public void testRejectsNothingAndEmptyNames() {
        Assert.assertFalse(WireClassFilter.isAllowed(null));
        Assert.assertFalse(WireClassFilter.isAllowed(""));
    }

    /**
     * A prefix allowlist must match on the package boundary, not on a string
     * prefix — otherwise a class in {@code forge.evil.Attack} style packaging,
     * or a package merely starting with the same letters, rides along.
     */
    @Test
    public void testPrefixesAreNotSubstringMatches() {
        Assert.assertFalse(WireClassFilter.isAllowed("evil.forge.Gadget"));
        Assert.assertFalse(WireClassFilter.isAllowed("notjava.util.ArrayList"));
        Assert.assertFalse(WireClassFilter.isAllowed("forgery.Gadget"));
    }
}
