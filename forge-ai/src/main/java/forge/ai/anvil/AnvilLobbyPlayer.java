package forge.ai.anvil;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;

import java.util.Set;

/**
 * Factory injecting PlayerControllerAnvil via IGameEntitiesFactory — the
 * zero-engine-change wiring from the override plan.
 */
public class AnvilLobbyPlayer extends LobbyPlayerAi {
    private final AnvilBridge bridge;
    private final Set<String> bridgedTags;

    public AnvilLobbyPlayer(String name, AnvilBridge bridge, Set<String> bridgedTags) {
        super(name, null);
        this.bridge = bridge;
        this.bridgedTags = bridgedTags;
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(new PlayerControllerAnvil(game, p, this, bridge, bridgedTags));
        return p;
    }
}
