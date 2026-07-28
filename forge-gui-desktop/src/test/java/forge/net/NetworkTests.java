package forge.net;

import org.testng.SkipException;

/**
 * Shared gate for network tests that stand up real servers over loopback.
 *
 * <p>The default suite already pays about 18 s for one networked game in
 * {@link NetworkPlayIntegrationTest}; the security tests behind this gate cost
 * several times that, and almost no contributor is touching the net layer. The
 * property is the one that class already uses, so there is a single switch
 * rather than two.
 */
final class NetworkTests {

    private NetworkTests() {
    }

    static void skipUnlessStressTestsEnabled() {
        if (!"true".equalsIgnoreCase(System.getProperty("run.stress.tests"))) {
            throw new SkipException("Network stress tests skipped. Use -Drun.stress.tests=true to run.");
        }
    }
}
