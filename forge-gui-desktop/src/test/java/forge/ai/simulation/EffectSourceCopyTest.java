package forge.ai.simulation;

import forge.game.Game;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Probe: does GameCopier preserve the effect-source link of a command-zone
 * Effect card?
 *
 * Provenance: 9/578 M4 drill positions crashed every makeCopy with
 * IndexOutOfBoundsException at StaticAbilityContinuous:900 —
 * getDefinedPlayers(..., "MayPlayPlayer", ...) resolving EMPTY mid-copy
 * (drill-crash2-rows.jsonl). Root cause: the Prepared mechanic
 * (AlterAttributeEffect) grants "MayPlayPlayer$ EffectSourceController" on a
 * command-zone Effect card, and copyGameState restored setEffectSource only
 * for BATTLEFIELD cards — the copied effect card's findEffectRoot came back
 * null while the original kept resolving through its retained pointer. The
 * fix restores the link for every copied card whose source was also copied.
 */
public class EffectSourceCopyTest extends SimulationTest {

    private static final String MAY_PLAY =
            "Mode$ Continuous | MayPlay$ True | MayPlayPlayer$ EffectSourceController"
            + " | EffectZone$ Command | AffectedDefined$ Remembered | AffectedZone$ Exile";

    @Test
    public void testCommandZoneEffectKeepsEffectSourceAcrossCopy() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);

        // The Prepared-spell shape (AlterAttributeEffect): a battlefield
        // source, an exiled spell, and a command-zone Effect card that
        // remembers the exiled card and grants MayPlay via EffectSource.
        Card source = addCard("Runeclaw Bear", p0);
        Card exiled = addCardToZone("Lightning Bolt", p0, ZoneType.Exile);

        Card eff = SpellAbilityEffect.createEffect(
                null, source, p0, source + "'s Prepared Spell", null,
                game.getNextTimestamp());
        eff.addRemembered(exiled);
        eff.addStaticAbility(MAY_PLAY);
        game.getAction().moveToCommand(eff, null);
        game.getAction().checkStateEffects(true); // resolves fine in the original

        Game copy = new GameCopier(game).makeCopy(); // threw IndexOOB before the fix

        Card copiedEff = null;
        for (Card c : copy.getPlayers().get(0).getCardsIn(ZoneType.Command)) {
            if (c.getName().equals(eff.getName())) {
                copiedEff = c;
            }
        }
        Assert.assertNotNull(copiedEff, "effect card missing from copied command zone");
        Assert.assertNotNull(copiedEff.getEffectSource(),
                "copied effect card lost its effect source");
        Assert.assertEquals(copiedEff.getEffectSource().getId(), source.getId(),
                "copied effect source must map to the copied source card");
        // The granted permission must survive: the copied exiled card's
        // mayPlay must name the copied source's controller.
        Card copiedExiled = null;
        for (Card c : copy.getPlayers().get(0).getCardsIn(ZoneType.Exile)) {
            if (c.getName().equals("Lightning Bolt")) {
                copiedExiled = c;
            }
        }
        Assert.assertNotNull(copiedExiled);
        Assert.assertFalse(copiedExiled.mayPlay(copy.getPlayers().get(0)).isEmpty(),
                "copied exiled card lost its MayPlay grant");
    }
}
