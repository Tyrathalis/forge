package forge.game.event;

import forge.game.player.PlayerView;
import forge.util.TextUtil;

/**
 * Fired when the game starts (or stops) blocking on a specific player's input.
 * Unlike GameEventPlayerPriority, this covers every Input wait — combat
 * declarations, resolution-time choices, mulligans — and fires after the
 * player's AwaitingInput view flag has been stamped, so handlers that sync
 * state to net clients push a view in which the flag is already current.
 */
public record GameEventAwaitingInput(PlayerView player, boolean awaiting) implements GameEvent {

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return TextUtil.concatWithSpace("AwaitingInput -", player.toString(), String.valueOf(awaiting));
    }
}
