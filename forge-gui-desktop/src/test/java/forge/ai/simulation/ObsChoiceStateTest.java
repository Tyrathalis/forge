package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.anvil.Obs;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;

/**
 * Obs schema v2 choice-state emission (boundary bundle 2026-08-21):
 * declared "as enters / as cast, choose ..." results are public table
 * state and must ride the entity record ("cho" kv); secret choices are
 * view-sourced and stay hidden until revealed.
 */
public class ObsChoiceStateTest extends SimulationTest {

    @Test
    public void chosenColorEmitted() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card enchant = addCard("Utopia Sprawl", p);
        enchant.setChosenColors(Lists.newArrayList("green"));
        String obs = Obs.stateJson(game);
        AssertJUnit.assertTrue("chosen color missing from obs: " + obs,
                obs.contains("\"cho\":{\"col\":[\"green\"]}"));
    }

    @Test
    public void secretTypeHiddenUntilRevealed() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card bear = addCard("Runeclaw Bear", p);
        bear.setSecretChosenType("Goblin");
        String obs = Obs.stateJson(game);
        AssertJUnit.assertFalse("secret chosen type leaked: " + obs,
                obs.contains("Goblin"));
        bear.revealChosenType();
        obs = Obs.stateJson(game);
        AssertJUnit.assertTrue("revealed chosen type missing: " + obs,
                obs.contains("\"typ\":\"Goblin\""));
    }

    @Test
    public void namedCardAndNumberEmitted() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card bear = addCard("Runeclaw Bear", p);
        bear.setNamedCards(Lists.newArrayList("Lightning Bolt"));
        bear.setChosenNumber(3);
        String obs = Obs.stateJson(game);
        AssertJUnit.assertTrue("named card missing: " + obs,
                obs.contains("\"nam\":[\"Lightning Bolt\"]"));
        AssertJUnit.assertTrue("chosen number missing: " + obs,
                obs.contains("\"num\":3"));
    }

    @Test
    public void noChoicesNoKv() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        addCard("Runeclaw Bear", p);
        String obs = Obs.stateJson(game);
        AssertJUnit.assertFalse("empty cho emitted: " + obs, obs.contains("\"cho\""));
    }
}
