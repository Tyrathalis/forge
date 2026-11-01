package forge.web.match;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.event.EventValueChangeType;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventZone;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.web.WebTestSupport;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class BrowserSoundsTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    // Fails if a land's sound is not found when the host plays no sound itself, or when the host has its own sounds off
    @Test
    public void aLandMakesItsColoursSound() {
        final CardView forest = CardView.getCardForUi(FModel.getMagicDb().getCommonCards().getCard("Forest"));
        final GameEventZone played = new GameEventZone(ZoneType.Battlefield, null, EventValueChangeType.Added, forest, null);
        final boolean hostSounds = FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_SOUNDS);
        try {
            for (final boolean on : new boolean[] { true, false }) {
                FModel.getPreferences().setPref(FPref.UI_ENABLE_SOUNDS, on);
                Assert.assertEquals(new BrowserSounds(() -> null, p -> false).soundFor(played).name(), "green_land");
            }
        } finally {
            FModel.getPreferences().setPref(FPref.UI_ENABLE_SOUNDS, hostSounds);
        }
    }

    // Fails if an attack nobody blocked makes the sound of a block
    @Test
    public void onlyABlockMakesTheBlockSound() {
        final CardView attacker = new CardView(1, null);
        final CardView blocker = new CardView(2, null);
        final BrowserSounds sounds = new BrowserSounds(() -> null, p -> false);
        // The engine lists an unblocked attacker as blocked by itself
        Assert.assertNull(sounds.soundFor(blocks(attacker, attacker)));
        Assert.assertEquals(sounds.soundFor(blocks(attacker, blocker)).name(), "block");
    }

    private static GameEventBlockersDeclared blocks(final CardView attacker, final CardView by) {
        final Multimap<CardView, CardView> pairs = HashMultimap.create();
        pairs.put(attacker, by);
        final Map<GameEntityView, Multimap<CardView, CardView>> blockers = new HashMap<>();
        blockers.put(null, pairs);
        return new GameEventBlockersDeclared((PlayerView) null, blockers);
    }
}
