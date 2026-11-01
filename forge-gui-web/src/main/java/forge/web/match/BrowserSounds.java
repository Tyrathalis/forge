package forge.web.match;

import forge.game.event.GameEvent;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventFlipCoin;
import forge.game.event.GameEventGameOutcome;
import forge.game.player.PlayerView;
import forge.sound.EventVisualizer;
import forge.sound.SoundEffectType;
import forge.web.protocol.ToBrowser.Sound;

import java.util.function.Predicate;
import java.util.function.Supplier;

/** Picks the sound a game event makes. Only the sound's name is sent, and the browser plays it. Two of the sounds depend on whose view this is, and the opening coin toss sends none. */
final class BrowserSounds {
    private final EventVisualizer sounds;

    BrowserSounds(final Supplier<PlayerView> currentPlayer, final Predicate<PlayerView> isLocal) {
        sounds = new EventVisualizer(null) {
            @Override
            public SoundEffectType visit(final GameEventGameOutcome event) {
                final PlayerView local = currentPlayer.get();
                return local != null && local.getLobbyPlayerName().equals(event.winningPlayerName())
                        ? SoundEffectType.WinDuel : SoundEffectType.LoseDuel;
            }

            // The page plays the opening toss's sound itself, in time with the coin (coin.ts)
            @Override
            public SoundEffectType visit(final GameEventFlipCoin event) {
                return event.startingToss() ? null : super.visit(event);
            }

            @Override
            public SoundEffectType visit(final GameEventBlockersDeclared event) {
                // The local player's own blocks made their sound when declared. An unblocked attacker is listed as blocked by itself
                final boolean blocked = event.blockers().values().stream()
                        .anyMatch(pairs -> pairs.entries().stream().anyMatch(pair -> !pair.getKey().equals(pair.getValue())));
                return blocked && !isLocal.test(event.defendingPlayer()) ? SoundEffectType.Block : null;
            }
        };
    }

    /** The sound this event makes, or null if it makes none. */
    Sound soundFor(final GameEvent event) {
        final SoundEffectType effect = event.visit(sounds);
        if (effect == null) {
            return null;
        }
        final String name = effect == SoundEffectType.ScriptedEffect
                ? sounds.getScriptedSoundEffectName(event) : effect.getResourceFileName();
        return name == null || name.isEmpty() ? null : new Sound(name, effect.isSynced());
    }
}
