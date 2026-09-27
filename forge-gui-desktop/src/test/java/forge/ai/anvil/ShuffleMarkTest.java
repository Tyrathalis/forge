package forge.ai.anvil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.github.luben.zstd.ZstdInputStream;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Anvil ADR-0121: every Player.shuffle on the store session's game lands as
 * {"k":"mark","m":"shuffle","p":seat} in the game's frame, in stream order;
 * a game without a store session writes nothing.
 */
public class ShuffleMarkTest extends SimulationTest {

    private static List<String> frameLines(File f) throws Exception {
        try (InputStream in = new ZstdInputStream(new FileInputStream(f))) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            List<String> out = new ArrayList<>();
            for (String line : bo.toString(StandardCharsets.UTF_8).split("\n")) {
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
            return out;
        }
    }

    @Test
    public void shuffleLandsAsAMarkForEitherSeat() throws Exception {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Runeclaw Bear", p0, ZoneType.Library);
            addCardToZone("Runeclaw Bear", p1, ZoneType.Library);
        }
        File dir = java.nio.file.Files.createTempDirectory("shufflemark").toFile();
        File f = new File(dir, "obs.jsonl.zst");
        try {
            Obs.open(f.getPath());
            Obs.startGame(0, 7L, game, "Constructed");
            p1.shuffle(null);
            p0.shuffle(null);
            Obs.endGame("ok", 0, 1, 1, false);
        } finally {
            Obs.close();
        }
        List<String> lines = frameLines(f);
        AssertJUnit.assertTrue(lines.get(0).startsWith("{\"k\":\"game\""));
        List<String> marks = new ArrayList<>();
        for (String l : lines) {
            if (l.startsWith("{\"k\":\"mark\"")) {
                marks.add(l);
            }
        }
        AssertJUnit.assertEquals(marks.toString(), 2, marks.size());
        AssertJUnit.assertTrue(marks.get(0), marks.get(0).contains("\"m\":\"shuffle\""));
        AssertJUnit.assertTrue(marks.get(0), marks.get(0).endsWith(",\"p\":1}"));
        AssertJUnit.assertTrue(marks.get(1), marks.get(1).endsWith(",\"p\":0}"));
        AssertJUnit.assertTrue(marks.get(0), marks.get(0).contains("\"t\":"));
        AssertJUnit.assertTrue(lines.get(lines.size() - 1).startsWith("{\"k\":\"end\""));
        // the library is still the same three cards (recording only)
        AssertJUnit.assertEquals(3, p0.getCardsIn(ZoneType.Library).size());
    }

    @Test
    public void noStoreSessionNoMark() throws Exception {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);
        addCardToZone("Runeclaw Bear", p0, ZoneType.Library);
        File dir = java.nio.file.Files.createTempDirectory("shufflemark2").toFile();
        File f = new File(dir, "obs.jsonl.zst");
        try {
            Obs.open(f.getPath());
            // no Obs.startGame for this game: a copy-like game with no session
            p0.shuffle(null);
        } finally {
            Obs.close();
        }
        AssertJUnit.assertEquals(0L, f.length());
    }
}
