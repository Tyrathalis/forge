package forge.web.match;

import forge.card.CardStateName;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.player.PlayerView;
import forge.gamemodes.net.DeltaPacket;
import forge.gui.card.CardDetailUtil;
import forge.web.images.Foil;
import forge.web.protocol.JsonCodec;
import forge.web.protocol.ToBrowser.CardFace;
import forge.web.protocol.ToBrowser.Detail;
import forge.web.protocol.ToBrowser.PlayerDetail;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What the zoom panel shows for a card or a player. The text is what the desktop client builds with CardDetailUtil. */
final class CardDetails {
    private CardDetails() {
    }

    static PlayerDetail player(final PlayerView player) {
        final List<String> lines = new ArrayList<>();
        final String[] parts = player.getDetails().split("\n");
        // The browser draws commander damage itself, so those lines are left out. In the engine's list they follow the cast counts
        final List<String> commander = player.getPlayerCommanderInfo();
        final int casts = player.getCommanders() == null ? 0 : 1 + player.getCommanders().size();
        final Set<String> damage = new HashSet<>();
        for (int i = casts; i < commander.size(); i++) {
            damage.add(commander.get(i).trim());
        }
        // The first line is the player's name, which is sent separately
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isBlank() && !damage.contains(parts[i].trim())) {
                lines.add(parts[i]);
            }
        }
        return new PlayerDetail(DeltaPacket.makeDeltaKey(player), player.getName(), lines);
    }

    /** The faces of a card that this viewer may see. mayFlip adds the other face, and is true only for a viewer allowed to see it. */
    static Detail card(final CardView card, final GameView game, final boolean mayView, final boolean mayFlip) {
        final List<CardFace> faces = new ArrayList<>();
        if (mayView) {
            faces.add(face(card, card.getCurrentState(), game));
            if (card.isSplitCard() && card.hasLeftSplitState() && card.hasRightSplitState()) {
                faces.add(face(card, card.getLeftSplitState(), game));
                faces.add(face(card, card.getRightSplitState(), game));
            } else if (mayFlip) {
                faces.add(face(card, card.getAlternateState(), game));
            }
        }
        return new Detail(DeltaPacket.makeDeltaKey(card), faces);
    }

    private static CardFace face(final CardView card, final CardStateView state, final GameView game) {
        final String pt = state.isCreature() ? state.getPower() + "/" + state.getToughness()
                : state.isPlaneswalker() ? state.getLoyalty()
                : state.isBattle() ? state.getDefense() : null;
        return new CardFace(state.getName(), JsonCodec.manaCost(state.getManaCost()),
                state.getType() == null ? "" : state.getType().toString(), pt,
                CardDetailUtil.composeCardText(state, game, true).trim(), Foil.key(card, state),
                state.getColors() == null ? 0 : state.getColors().getColor(),
                StringUtils.isEmpty(state.getSetCode()) ? null : state.getSetCode(),
                state.getRarity(), turn(card, state));
    }

    /** The same turns as the desktop preview, except for an Aftermath card. It is printed upright, so only its second half turns, anticlockwise. */
    private static Integer turn(final CardView card, final CardStateView state) {
        if (card.isFaceDown()) {
            return null;
        }
        if (card.isSplitCard()) {
            // A view fills in keywords for its current state only, so the half's rules text is read instead
            final boolean aftermath = card.hasRightSplitState() && card.getRightSplitState().getOracleText().contains("Aftermath");
            if (!aftermath) {
                return 90;
            }
            return state.getState() == CardStateName.RightSplit ? Integer.valueOf(270) : null;
        }
        return state.isPlane() || state.isPhenomenon() ? 90 : null;
    }
}
