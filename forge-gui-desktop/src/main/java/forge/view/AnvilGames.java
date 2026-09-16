package forge.view;

import forge.game.Game;

/**
 * Anvil (ADR-0110, the 2026-09-16 upstream merge): harness-side game setup
 * shared by AnvilRun / CensusRun / ForkFidelityCheck.
 */
final class AnvilGames {
    private AnvilGames() {
    }

    /** Upstream #11780 (2026-09-03) skips the GUI's ability-text refresh on a
     *  game marked {@code setNoGUIUser()} (a {@code DummyCardView} per card);
     *  GameCopier marks every search copy. The 09-14 JFR read put the view
     *  layer at 18–20% of a headless worker's CPU; the mainline game is
     *  marked here (default on; {@code -Danvil.nogui=off} restores the GUI views)
     *  — the flag-on vs flag-off forkcheck on one jar was the ADR-0025 proof. Must run before any card of the game is created
     *  (Card's constructor picks the view class), i.e. right after
     *  {@code Match.createGame()} and before {@code startGame}. */
    static void noGui(Game game) {
        // default ON since the flag-on forkcheck run-20260916-merge-nogui read
        // identical to the flag-off baseline (ADR-0110); -Danvil.nogui=off restores
        if (!"off".equals(System.getProperty("anvil.nogui", "on"))) {
            game.setNoGUIUser();
        }
    }
}
