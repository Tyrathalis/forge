package forge.chronicle;

import java.util.ArrayList;
import java.util.List;

import forge.Forge;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleKitchen;
import forge.gamemodes.chronicle.ChronicleRival;
import forge.gamemodes.match.HostedMatch;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.player.GamePlayerUtil;
import forge.screens.LoadingOverlay;
import forge.toolbox.FOptionPane;

/**
 * Launches a kitchen-table game and brings the verdict back.
 *
 * Notably this needs NO change to any shared file. Adventure reports its
 * results through an {@code isMobileAdventureMode} branch inside
 * MatchController.finishGame(); a second mode-specific branch there is exactly
 * what Chronicle's isolation convention exists to avoid, and it is unnecessary
 * — HostedMatch's own endGameHook fires when a game finishes, and the outcome
 * is readable from there. Chronicle keeps its entanglement budget at zero and
 * the player gets Forge's standard win/lose screen.
 *
 * Two rules deliberately pinned here rather than inherited:
 *
 * - <b>One game, not a match.</b> Best-of-three is a tournament construct; the
 *   kitchen table plays a game. It also makes the purse unambiguous.
 * - <b>Ante is forced OFF.</b> The global UI_ANTE preference must never reach
 *   this table: ADR-0071 makes ante an opt-in stake, and the opt-in does not
 *   exist yet. Inheriting the preference would silently gamble the player's
 *   collection — the one thing a collection game must never do by accident.
 */
public final class ChronicleMatch {

    private ChronicleMatch() {
    }

    /**
     * Play {@code playerDeck} against a rival. The purse is credited when the
     * game ends; {@code onFinished} runs on the EDT afterwards so the caller can
     * refresh.
     */
    public static void play(ChronicleController.Challenge challenge, Deck playerDeck, Runnable onFinished) {
        ChronicleController controller = ChronicleHub.controller();
        ChronicleRival rival = challenge.rival;

        LoadingOverlay.show(caption("lblChronicleSittingDown", "Sitting down to play..."), true, () -> {
            final HostedMatch hostedMatch = GuiBase.getInterface().hostMatch();
            //fires once per finished game; gamesPerMatch is 1, and the flag keeps a
            //re-entrant call from paying twice
            final boolean[] recorded = { false };
            hostedMatch.setEndGameHook(() -> {
                if (recorded[0]) {
                    return;
                }
                recorded[0] = true;
                boolean won = readWin(hostedMatch);
                FThreads.invokeInEdtLater(() -> {
                    ChronicleKitchen.Result result = controller.recordMatch(rival, playerDeck.getName(), won);
                    announce(rival, result);
                    if (onFinished != null) {
                        onFinished.run();
                    }
                });
            });

            List<RegisteredPlayer> players = new ArrayList<>();
            RegisteredPlayer human = new RegisteredPlayer(playerDeck).setPlayer(GamePlayerUtil.getGuiPlayer());
            players.add(human);
            players.add(new RegisteredPlayer(challenge.rivalDeck).setPlayer(GamePlayerUtil.createAiPlayer(rival.name)));

            GameRules rules = new GameRules(GameType.Constructed);
            rules.setGamesPerMatch(1);
            rules.setPlayForAnte(false);
            rules.setManaBurn(false);
            rules.setWarnAboutAICards(false);

            hostedMatch.startMatch(rules, null, players, human, GuiBase.getInterface().getNewGuiGame());
        });
    }

    /** The engine's verdict, never Chronicle's. Defensive: a torn-down game reads as a loss, not a crash. */
    private static boolean readWin(HostedMatch hostedMatch) {
        try {
            return hostedMatch.getGame().getOutcome().isWinner(GamePlayerUtil.getGuiPlayer());
        } catch (RuntimeException e) {
            System.err.println("Chronicle: could not read the match outcome — " + e);
            return false;
        }
    }

    private static void announce(ChronicleRival rival, ChronicleKitchen.Result result) {
        String message;
        if (!result.won) {
            message = rival.name + " " + caption("lblChronicleTookThatOne", "took that one.");
        } else if (result.paid) {
            message = caption("lblChronicleYouWin", "You win!") + "\n"
                    + rival.name + " " + caption("lblChroniclePaysUp", "pays up") + ": "
                    + ChronicleHomeScreen.formatCents(result.purseCents);
        } else {
            //a rematch: the game was real, the money was already collected today
            message = caption("lblChronicleYouWin", "You win!") + "\n"
                    + caption("lblChronicleAlreadyPaidToday", "Just for the fun of it — you already took their money today.");
        }
        FOptionPane.showMessageDialog(message, rival.name);
    }

    private static String caption(String key, String fallback) {
        return Forge.getLocalizer().getMessageorUseDefault(key, fallback);
    }
}
