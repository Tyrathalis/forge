package forge.ai.anvil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;

/**
 * Anvil M10 cousins touch (2026-08-28): directed execution of cousin
 * commitments (convoke taps / improvise taps / delve exiles).
 *
 * The engine applies cousins inside CostAdjustment.adjust(ManaCostBeingPaid,…)
 * via controller callbacks — there is no express-choice mechanism — so a
 * directed plan is ARMED here by the payment window owner (the bridged
 * path, the schedule directive, or the certify PayDirective) immediately
 * before the auto-completion runs, and the generated census controller's
 * FORCE_OVERRIDES hooks consume it from inside the nested
 * chooseCardsForConvokeOrImprovise / chooseCardsToDelve callbacks. Unarmed
 * callbacks return null = natural (heuristic AI unchanged — the ADR-0025
 * identity direction). Arm/disarm is strictly scoped to one synchronous
 * payment window on the game thread; disarm lives in a finally.
 *
 * The callback returns the shard assignment itself (Map&lt;Card, ManaCostShard&gt;)
 * and CostAdjustment applies it unvalidated — legality discipline is the
 * enumerator's (cousinCanPay mirrors payManaViaConvoke / decreaseGenericMana).
 */
public final class CousinDirective {

    /** One armed payment's cousin plan + consumption telemetry. */
    public static final class Armed {
        final PaymentEnumerator.PaymentClass plan;
        /** Entries returned to the engine per mechanism. */
        public int convokeServed;
        public int improviseServed;
        public int delveServed;
        /** Planned cards the engine's offered list no longer contained. */
        public int misses;

        Armed(PaymentEnumerator.PaymentClass plan) {
            this.plan = plan;
        }

        public String summary() {
            return "cvk:" + convokeServed + "/" + plan.convokeTaps.size()
                    + ",imp:" + improviseServed + "/" + plan.improviseTaps.size()
                    + ",dlv:" + delveServed + "/" + plan.delveExiles.size()
                    + (misses > 0 ? ",miss:" + misses : "");
        }
    }

    private static final Map<Player, Armed> ARMED = new WeakHashMap<>();

    private CousinDirective() {
    }

    /** Arm the payer's cousin plan for the imminent auto-completion.
     *  Returns the Armed record (telemetry read-back after disarm). */
    public static Armed arm(final Player p, final PaymentEnumerator.PaymentClass pc) {
        final Armed a = new Armed(pc);
        synchronized (ARMED) {
            ARMED.put(p, a);
        }
        return a;
    }

    public static void disarm(final Player p) {
        synchronized (ARMED) {
            ARMED.remove(p);
        }
    }

    private static Armed of(final Player p) {
        synchronized (ARMED) {
            return ARMED.get(p);
        }
    }

    /** FORCE_OVERRIDES hook: convoke (creatures) / improvise (artifacts) /
     *  waterbend (both — never armed: waterbend spells stay costmod).
     *  null = unarmed = natural heuristic play. */
    public static Map<Card, ManaCostShard> forceConvokeOrImprovise(final forge.game.Game g,
            final Player p, final forge.game.spellability.SpellAbility sa,
            final CardCollectionView untappedCards, final boolean artifacts, final boolean creatures) {
        final Armed a = of(p);
        if (a == null) {
            return null;
        }
        final Map<Card, ManaCostShard> out = new LinkedHashMap<>();
        if (creatures) {
            for (final Map.Entry<Card, ManaCostShard> e : a.plan.convokeTaps.entrySet()) {
                if (untappedCards.contains(e.getKey())) {
                    out.put(e.getKey(), e.getValue());
                    a.convokeServed++;
                } else {
                    a.misses++;
                }
            }
        }
        if (artifacts) {
            for (final Map.Entry<Card, ManaCostShard> e : a.plan.improviseTaps.entrySet()) {
                if (untappedCards.contains(e.getKey())) {
                    out.put(e.getKey(), e.getValue());
                    a.improviseServed++;
                } else {
                    a.misses++;
                }
            }
        }
        return out;
    }

    /** FORCE_OVERRIDES hook: delve exiles. null = unarmed = natural. */
    public static CardCollectionView forceDelve(final forge.game.Game g, final Player p,
            final CardCollection grave) {
        final Armed a = of(p);
        if (a == null) {
            return null;
        }
        final CardCollection out = new CardCollection();
        for (final Card c : a.plan.delveExiles) {
            if (grave.contains(c)) {
                out.add(c);
                a.delveServed++;
            } else {
                a.misses++;
            }
        }
        return out;
    }
}
