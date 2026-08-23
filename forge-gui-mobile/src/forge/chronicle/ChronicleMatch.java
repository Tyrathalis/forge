package forge.chronicle;

import java.util.ArrayList;
import java.util.List;

import forge.Forge;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.GameOutcome;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleKitchen;
import forge.gamemodes.chronicle.ChronicleRival;
import forge.gamemodes.match.HostedMatch;
import forge.item.PaperCard;
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
 * - <b>Ante is never inherited from the global UI_ANTE preference.</b> It is on
 *   only when the player chose this specific game to be played for keeps.
 *   Silently gambling a collection is the one thing a collection game must never
 *   do by accident, and here the loss is permanent — seed integrity forbids the
 *   reroll. Rarity is matched and basics are excluded, so a rare is only ever
 *   staked against a rare.
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
        play(challenge, playerDeck, false, onFinished);
    }

    /** @param forKeeps play for ante — both sides stake a rarity-matched card from their deck. */
    public static void play(ChronicleController.Challenge challenge, Deck playerDeck, boolean forKeeps,
                            Runnable onFinished) {
        ChronicleController controller = ChronicleHub.controller();
        ChronicleRival rival = challenge.rival;

        LoadingOverlay.show(caption("lblChronicleSittingDown", "Sitting down to play..."), true, () -> {
            final HostedMatch hostedMatch = GuiBase.getInterface().hostMatch();
            //fires once per finished game; gamesPerMatch is 1, and the flag keeps a
            //re-entrant call from paying twice
            final boolean[] recorded = { false };
            final RegisteredPlayer[] human = new RegisteredPlayer[1];
            hostedMatch.setEndGameHook(() -> {
                if (recorded[0]) {
                    return;
                }
                recorded[0] = true;
                boolean won = readWin(hostedMatch);
                final GameOutcome.AnteResult ante = forKeeps ? readAnte(hostedMatch, human[0]) : null;
                FThreads.invokeInEdtLater(() -> {
                    ChronicleKitchen.Result result = controller.recordMatch(rival, playerDeck.getName(), won,
                            ante == null ? null : ante.wonCards,
                            ante == null ? null : ante.lostCards);
                    announce(rival, result);
                    if (onFinished != null) {
                        onFinished.run();
                    }
                });
            });

            List<RegisteredPlayer> players = new ArrayList<>();
            human[0] = new RegisteredPlayer(playerDeck).setPlayer(GamePlayerUtil.getGuiPlayer());
            players.add(human[0]);
            players.add(new RegisteredPlayer(challenge.rivalDeck).setPlayer(GamePlayerUtil.createAiPlayer(rival.name)));

            GameRules rules = new GameRules(GameType.Constructed);
            rules.setGamesPerMatch(1);
            rules.setPlayForAnte(forKeeps);
            rules.setMatchAnteRarity(true);       //a rare is only ever staked against a rare
            rules.setAnteIncludeBasicLands(false);
            rules.setManaBurn(false);
            rules.setWarnAboutAICards(false);

            hostedMatch.startMatch(rules, null, players, human[0], GuiBase.getInterface().getNewGuiGame());
        });
    }

    /** What actually changed hands. Null on any doubt — never invent a card movement. */
    private static GameOutcome.AnteResult readAnte(HostedMatch hostedMatch, RegisteredPlayer human) {
        try {
            return human == null ? null : hostedMatch.getAnteResult(human);
        } catch (RuntimeException e) {
            System.err.println("Chronicle: could not read the ante result — " + e);
            return null;
        }
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
        String message = purseLine(rival, result);
        boolean anteMoved = !result.anteWon.isEmpty() || !result.anteLost.isEmpty();
        if (result.won && !result.paid && !anteMoved) {
            //a cash rematch: say why no money changed hands, so it doesn't read as a bug
            message += "\n" + caption("lblChronicleAlreadyPaidToday",
                    "Just for the fun of it — you already took their money today.");
        }
        //cards changing hands is the headline when it happens
        if (!result.anteWon.isEmpty()) {
            message += "\n" + caption("lblChronicleYouTake", "You take") + ": " + names(result.anteWon);
        }
        if (!result.anteLost.isEmpty()) {
            message += "\n" + rival.name + " " + caption("lblChronicleTakes", "takes") + ": " + names(result.anteLost);
        }
        FOptionPane.showMessageDialog(message, rival.name);
    }

    private static String names(List<PaperCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (PaperCard card : cards) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(card.getName());
        }
        return sb.toString();
    }

    private static String purseLine(ChronicleRival rival, ChronicleKitchen.Result result) {
        String message;
        if (!result.won) {
            message = rival.name + " " + caption("lblChronicleTookThatOne", "took that one.");
        } else if (result.paid) {
            message = caption("lblChronicleYouWin", "You win!") + "\n"
                    + rival.name + " " + caption("lblChroniclePaysUp", "pays up") + ": "
                    + ChronicleHomeScreen.formatCents(result.purseCents);
        } else {
            //a rematch: the game was real, the money was already collected today
            message = caption("lblChronicleYouWin", "You win!");
        }
        return message;
    }

    private static String caption(String key, String fallback) {
        return Forge.getLocalizer().getMessageorUseDefault(key, fallback);
    }
}
