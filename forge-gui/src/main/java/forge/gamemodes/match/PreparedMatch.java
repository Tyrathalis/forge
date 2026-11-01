package forge.gamemodes.match;

import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;

import java.util.List;
import java.util.Set;

/** A match a game mode has built, which a client only has to start. */
public record PreparedMatch(GameRules rules, Set<GameType> variants, List<RegisteredPlayer> players, RegisteredPlayer human) {
}
