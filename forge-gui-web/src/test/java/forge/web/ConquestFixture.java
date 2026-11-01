package forge.web;

import forge.gamemodes.planarconquest.ConquestController;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestPreferences;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.FileUtil;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Creates conquests in the test profile for a test, and removes them afterwards. */
final class ConquestFixture {
    private static final List<ConquestData> made = new ArrayList<>();
    private static final List<Path> junk = new ArrayList<>();
    private static String currentBefore;

    private ConquestFixture() {
    }

    static String name() {
        return "Conquest test " + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A new conquest on a plane, with the plane's first commander and that commander's first planeswalker. */
    static ConquestData create(final String planeName) {
        return create(planeName, name());
    }

    static ConquestData create(final String planeName, final String name) {
        if (currentBefore == null) {
            currentBefore = FModel.getConquestPreferences().getPref(CQPref.CURRENT_CONQUEST);
        }
        final ConquestPlane plane = FModel.getPlanes().get(planeName);
        final PaperCard commander = plane.getCommanders().get(0);
        final PaperCard planeswalker = ConquestUtil.getStartingPlaneswalkerOptions(commander).iterator().next();
        final ConquestData data = FModel.getConquest().create(name, plane, planeswalker, commander);
        made.add(data);
        return data;
    }

    /** Copies a saved conquest into the profile under a new name. It has Zendikar, the first event won, 3000 shards, 1 emblem, 42 cards and a 40-card deck. */
    static ConquestData install() throws IOException, URISyntaxException {
        if (currentBefore == null) {
            currentBefore = FModel.getConquestPreferences().getPref(CQPref.CURRENT_CONQUEST);
        }
        final Path from = Path.of(ConquestFixture.class.getResource("/conquest/Fixture_conquest").toURI());
        final Path to = Path.of(ForgeConstants.CONQUEST_SAVE_DIR, name().replace(' ', '_'));
        try (Stream<Path> files = Files.walk(from)) {
            for (final Path file : (Iterable<Path>) files::iterator) {
                final Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        final ConquestData data = new ConquestData(to.toFile());
        made.add(data);
        return data;
    }

    /** A name that a test will create or rename a conquest to. Its folder is removed afterwards. */
    static String expected() {
        if (currentBefore == null) {
            currentBefore = FModel.getConquestPreferences().getPref(CQPref.CURRENT_CONQUEST);
        }
        final String name = name();
        junk.add(Path.of(ForgeConstants.CONQUEST_SAVE_DIR, name.replace(' ', '_')));
        return name;
    }

    /** Writes a save folder whose data cannot be read, and returns the name it would be listed under. */
    static String broken(final String xml) throws IOException {
        final String name = name();
        final Path dir = Path.of(ForgeConstants.CONQUEST_SAVE_DIR, name.replace(' ', '_'));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("data.xml"), xml);
        junk.add(dir);
        return name;
    }

    /** The first place on a plane whose event opens a secret plane, or null. */
    static ConquestLocation portal(final ConquestPlane plane) {
        for (int region = 0; region < plane.getRegions().size(); region++) {
            for (int row = 0; row < plane.getRowsPerRegion(); row++) {
                for (int col = 0; col < plane.getCols(); col++) {
                    final ConquestLocation loc = new ConquestLocation(plane, region, row, col);
                    if (loc.getEvent().getTemporaryUnlock() != null) {
                        return loc;
                    }
                }
            }
        }
        return null;
    }

    static void cleanUp() {
        FModel.getConquest().cancelBattle();
        FModel.getConquest().setModel(null);
        for (final ConquestData data : made) {
            ConquestController.delete(data);
        }
        made.clear();
        for (final Path dir : junk) {
            FileUtil.deleteDirectory(dir.toFile());
        }
        junk.clear();
        // The whole process shares the portal flag
        for (final ConquestPlane plane : FModel.getPlanes()) {
            if (List.of("Time_Vault", "Unstable_Realm").contains(plane.getName())) {
                plane.setTemporarilyReachable(false);
            }
        }
        if (currentBefore != null) {
            final ConquestPreferences prefs = FModel.getConquestPreferences();
            prefs.setPref(CQPref.CURRENT_CONQUEST, currentBefore);
            prefs.save();
            currentBefore = null;
        }
    }
}
