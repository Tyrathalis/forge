package forge.ai.anvil;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;

/**
 * LobbyPlayerAi variant whose factory injects CensusPlayerController — the
 * same IGameEntitiesFactory wiring PlayerControllerAnvil will use.
 */
public class CensusLobbyPlayer extends LobbyPlayerAi {
    public CensusLobbyPlayer(String name) {
        super(name, null);
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(new CensusPlayerController(game, ai, this));
        return ai;
    }
}
