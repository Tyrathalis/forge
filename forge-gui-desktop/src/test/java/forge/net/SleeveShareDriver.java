package forge.net;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import forge.deck.Deck;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.localinstance.properties.ForgeConstants;
import forge.util.CustomSleeves;
import forge.util.SleeveExchange;
import forge.util.SleeveStore;

/**
 * Two-process check that a custom sleeve actually reaches another player.
 *
 * <p>Run as a host and a client in separate JVMs with separate profile directories, so "the sleeve
 * arrived" cannot be confused with "it was already on disk" - the thing a single-process test
 * cannot tell apart, since both sides would share one store.
 *
 * <p>Prints its own directories on start: the separation is demonstrated rather than assumed.
 *
 * <p>Not a TestNG test, because surefire's argLine is fixed in the parent pom and a per-fork
 * {@code user.home} cannot be passed through it - a test that could never run would only ever show
 * up as a skip. Run the two halves by hand instead, from {@code forge-gui/} so {@code res/}
 * resolves:
 *
 * <pre>
 * R=..;  CP="$R/forge-gui-desktop/target/test-classes:$R/forge-gui-desktop/target/classes:\
 *            $R/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar:\
 *            ~/.m2/repository/org/testng/testng/7.10.2/testng-7.10.2.jar"
 * java -Duser.home=/tmp/host   -cp "$CP" forge.net.SleeveShareDriver host   36000 120000
 * java -Duser.home=/tmp/client -cp "$CP" forge.net.SleeveShareDriver client localhost 36000 sleeve.png 20000
 * java -Duser.home=/tmp/client -cp "$CP" forge.net.SleeveShareDriver tamper localhost 36000 sleeve.png 15000
 * </pre>
 *
 * The tamper run needs a second image at {@code sleeve.png.other}; the host must refuse it and
 * write nothing.
 *
 * <p>Verified 2026-08-22: the honest run landed a byte-identical copy in the host's session
 * directory while the host's own library did not exist, and the tamper run was refused with
 * "the image does not match the sleeve it claims to be".
 */
public final class SleeveShareDriver {
    private SleeveShareDriver() {}

    public static void main(final String[] args) throws Exception {
        run(args);
    }

    /** Same entry point, without the System.exit, so a test can host either half. */
    public static void run(final String[] args) throws Exception {
        System.out.println("[driver] role=" + args[0]);
        TestUtils.ensureFModelInitialized();
        System.out.println("[driver] USER_DIR  = " + ForgeConstants.USER_DIR);
        System.out.println("[driver] CACHE_DIR = " + ForgeConstants.CACHE_DIR);
        System.out.println("[driver] sleeves   = " + SleeveStore.directory().getAbsolutePath());
        System.out.println("[driver] session   = " + SleeveExchange.sessionDirectory().getAbsolutePath());

        if ("host".equals(args[0])) {
            host(Integer.parseInt(args[1]), Long.parseLong(args[2]));
        } else if ("tamper".equals(args[0])) {
            tamper(args[1], Integer.parseInt(args[2]), args[3], Long.parseLong(args[4]));
        } else {
            client(args[1], Integer.parseInt(args[2]), args[3], Long.parseLong(args[4]));
        }
    }

    private static void host(final int port, final long holdMs) throws Exception {
        SleeveExchange.clearSession();
        System.out.println("[host] session dir starts with: " + Arrays.toString(list(SleeveExchange.sessionDirectory())));

        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        server.setLobby(new ServerGameLobby());
        System.out.println("[host] READY on port " + port);

        Thread.sleep(holdMs);

        final String[] arrived = list(SleeveExchange.sessionDirectory());
        System.out.println("[host] session dir now holds: " + Arrays.toString(arrived));
        for (final String name : arrived) {
            final File f = new File(SleeveExchange.sessionDirectory(), name);
            final byte[] bytes = Files.readAllBytes(f.toPath());
            final boolean intact = name.startsWith(CustomSleeves.sha256Hex(bytes));
            System.out.println("[host] RESULT received=" + name + " bytes=" + bytes.length
                    + " hashMatchesName=" + intact);
        }
        if (arrived.length == 0) {
            System.out.println("[host] RESULT nothing arrived");
        }
        server.stopServer();
    }

    private static void client(final String hostname, final int port, final String imagePath,
            final long holdMs) throws Exception {
        final byte[] image = Files.readAllBytes(new File(imagePath).toPath());
        final SleeveStore.Result stored = SleeveStore.save(image);
        if (stored.error != null) {
            throw new IllegalStateException("could not store the sleeve: " + stored.error);
        }
        System.out.println("[client] holding sleeve " + stored.key + " (" + image.length + " bytes)");

        try (HeadlessNetworkClient client = new HeadlessNetworkClient("SleeveTester", hostname, port)) {
            if (!client.connect(30_000)) {
                throw new IllegalStateException("could not connect");
            }
            System.out.println("[client] connected, slot " + client.getAssignedSlot());
            Thread.sleep(1000);

            // The real path: a lobby update naming the sleeve, which is what triggers the send
            final Deck deck = new Deck("Sleeve Test");
            deck.setSleeveArtKey(stored.key);
            client.getClient().send(UpdateLobbyPlayerEvent.deckUpdate(deck));
            System.out.println("[client] sent a lobby update naming the sleeve");

            Thread.sleep(holdMs);
        }
    }

    /**
     * What a modified client would do: offer one sleeve's name with a different sleeve's bytes.
     * Nothing in the protocol stops it being sent - the recipient is what stops it being believed.
     */
    private static void tamper(final String hostname, final int port, final String imagePath,
            final long holdMs) throws Exception {
        final byte[] real = Files.readAllBytes(new File(imagePath).toPath());
        final byte[] substituted = Files.readAllBytes(new File(imagePath + ".other").toPath());
        final String honestKey = CustomSleeves.keyFor(CustomSleeves.sha256Hex(real));
        System.out.println("[tamper] claiming " + honestKey);
        System.out.println("[tamper] actually sending bytes that hash to "
                + CustomSleeves.sha256Hex(substituted));

        try (HeadlessNetworkClient client = new HeadlessNetworkClient("Tamperer", hostname, port)) {
            if (!client.connect(30_000)) {
                throw new IllegalStateException("could not connect");
            }
            System.out.println("[tamper] connected, slot " + client.getAssignedSlot());
            Thread.sleep(1000);
            client.getClient().send(new forge.gamemodes.net.event.SleeveBlobEvent(honestKey, substituted));
            System.out.println("[tamper] sent a mismatched blob");
            Thread.sleep(holdMs);
        }
    }

    private static String[] list(final File dir) {
        final String[] names = dir.list();
        return names == null ? new String[0] : names;
    }
}
