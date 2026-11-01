package forge.web.match;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
import forge.trackable.TrackableProperty;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import forge.web.protocol.ToBrowser.Block;
import forge.web.protocol.ToBrowser.BlockersDeclared;
import forge.web.protocol.ToBrowser.CardMoved;
import forge.web.protocol.ToBrowser.Ref;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

public class BrowserEventsTest {
    /** Netplay sends a cast spell's card as a snapshot with no controller, so the move must get its caster from the tracker. */
    @Test
    public void castSnapshotNamesItsCasterFromTheTracker() {
        final Tracker tracker = new Tracker();
        final PlayerView caster = new PlayerView(7, tracker);
        final CardView onStack = new CardView(51, tracker, "Floodpits Drowner");
        onStack.set(TrackableProperty.Controller, caster);
        tracker.putObj(TrackableTypes.CardViewType, 51, onStack);
        final CardView snapshot = new CardView(51, tracker, "Floodpits Drowner");

        final Object moved = BrowserEvents.forwarded(new GameEventCardChangeZone(snapshot,
                new ZoneView(caster, ZoneType.Hand), new ZoneView(null, ZoneType.Stack)), tracker);

        Assert.assertEquals(((CardMoved) moved).caster(), Ref.player(7));
    }

    /** The engine lists an unblocked attacker as blocked by itself, which must not be sent as a block. */
    @Test
    public void blocksLeaveOutAnUnblockedAttacker() {
        final Tracker tracker = new Tracker();
        final PlayerView defender = new PlayerView(7, tracker);
        final CardView blocked = new CardView(51, tracker, "Grizzly Bears");
        final CardView unblocked = new CardView(52, tracker, "Hill Giant");
        final CardView blocker = new CardView(53, tracker, "Wall of Wood");
        final Multimap<CardView, CardView> blockers = ArrayListMultimap.create();
        blockers.put(blocked, blocker);
        blockers.put(unblocked, unblocked);

        final Object declared = BrowserEvents.forwarded(new GameEventBlockersDeclared(defender, Map.<GameEntityView, Multimap<CardView, CardView>>of(defender, blockers)), tracker);

        Assert.assertEquals(((BlockersDeclared) declared).blocks(), List.of(new Block(Ref.card(53), Ref.card(51))));
    }
}
