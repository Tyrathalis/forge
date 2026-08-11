package forge.ai.anvil;

import com.google.common.collect.Lists;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Priority option candidates, materialized once per window (the
 * legal-actions-only invariant, ADR-0001). Shared by the bridged path
 * (PlayerControllerAnvil, M0) and the corpus label path (Obs.decPriority,
 * M1 D2) so both log the same basis: timing-legal spell abilities (see
 * PAYCHECK note — payability is deliberately NOT filtered) plus legal land
 * drops. Pass is not an option here — callers represent it themselves
 * (index 0 on the bridge; a null answer in the log).
 */
public final class AnvilOptions {
    private AnvilOptions() {
    }

    /**
     * The logged option set is TIMING-LEGAL CANDIDATES, not payable actions
     * (M1 D3 decision). Exact payability is not cheaply computable at scan
     * time: cost reductions/additional costs are priced only after the AI's
     * canPlaySa sets up targets and X ("can only be checked late" —
     * AiController.canPlayAndPayForFace), which is why the old canPayCost
     * filter both diverged from the expert's own picks (Mystical Dispute,
     * Dargo, X spells — D3's 320-game validation, 11 errors) and duplicated
     * the AI's most expensive work per window. canPlay() is the same
     * predicate the AI itself requires (Spell.canPlay == canPlayFromHost
     * != null), so the set is a superset of the expert's castable actions
     * by construction; affordability is the model's to learn (it must price
     * costs anyway to emit CastPlans). -Danvil.scan.paycheck=on restores the
     * old filter for comparison runs only.
     */
    private static final boolean PAYCHECK =
            "on".equals(System.getProperty("anvil.scan.paycheck", "off"));

    /**
     * Mask cache (2026-08-11): priorityOptions dominated bridged-generation
     * engine time (55.5% of in-game samples) and 64.3% of priority asks
     * repeat the seat's previous mask verbatim (pass-pass chains). Reuse is
     * safe ONLY across pure pass chains: canPlaySa mutates SA state
     * (targets/X) on any cast attempt, so the controller invalidates the
     * seat's entry on every non-pass answer (see
     * PlayerControllerAnvil). The key is conservative — any mask-relevant
     * transition the game timestamp does not bump (phase boundaries, land
     * drops, stack movement, turn-scoped expiries) is a key component; a
     * false rebuild costs only the old price. DEFAULT OFF
     * (-Danvil.scan.maskcache=on enables): equivalence-proven (obs-diff gate
     * 2026-08-11, 3,346/3,346 windows byte-identical, identical outcomes)
     * but throughput-neutral at the loop's serving-bound operating point,
     * and the key is a staleness surface a future engine rebase could
     * silently widen — re-run the obs-diff gate before re-enabling.
     */
    private static final boolean MASK_CACHE =
            "on".equals(System.getProperty("anvil.scan.maskcache", "off"));

    private static final class SeatEntry {
        long timestamp;
        int turn;
        String phase;
        int stackSize;
        int landsPlayed;
        long stateHash;
        List<SpellAbility> mask;
    }

    /**
     * Cheap content fingerprint covering mask inputs the game timestamp does
     * NOT version (found by the obs-diff gate, 2026-08-11): hand/command/
     * exile/graveyard arrivals (hidden-zone moves skip the timestamp bump —
     * the tutor-to-hand staleness), and tapped state (mana-ability options).
     * ~50 card reads vs a 55%-of-engine-time rebuild.
     */
    private static long seatStateHash(Player player) {
        long h = 1469598103934665603L;
        for (forge.game.zone.ZoneType zt : STATE_ZONES) {
            for (Card c : player.getZone(zt)) {
                h = h * 1099511628211L
                        ^ (c.getId() * 2L + (c.isTapped() ? 1L : 0L));
            }
            h *= 31L; // zone boundary
        }
        return h;
    }

    private static final forge.game.zone.ZoneType[] STATE_ZONES = {
            forge.game.zone.ZoneType.Hand, forge.game.zone.ZoneType.Battlefield,
            forge.game.zone.ZoneType.Command, forge.game.zone.ZoneType.Graveyard,
            forge.game.zone.ZoneType.Exile };

    private static final java.util.Map<Game, java.util.Map<Player, SeatEntry>> CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Drop the seat's cached mask (controller calls this on every non-pass
     *  answer — cached SpellAbility objects are dirtied by canPlaySa). */
    public static void invalidate(Game game, Player player) {
        java.util.Map<Player, SeatEntry> perSeat = CACHE.get(game);
        if (perSeat != null) {
            perSeat.remove(player);
        }
    }

    public static List<SpellAbility> priorityOptions(Game game, Player player) {
        if (!MASK_CACHE) {
            return buildPriorityOptions(game, player);
        }
        java.util.Map<Player, SeatEntry> perSeat = CACHE.computeIfAbsent(
                game, ignored -> new java.util.concurrent.ConcurrentHashMap<>());
        SeatEntry e = perSeat.get(player);
        long ts = game.getTimestamp();
        int turn = game.getPhaseHandler().getTurn();
        String phase = String.valueOf(game.getPhaseHandler().getPhase());
        // MagicStack, not the stack ZONE: ability entries never enter the
        // zone, and sorcery-speed legality keys on true stack occupancy.
        int stackSize = game.getStack().size();
        int landsPlayed = player.getLandsPlayedThisTurn();
        long stateHash = seatStateHash(player);
        if (e != null && e.timestamp == ts && e.turn == turn
                && e.stackSize == stackSize && e.landsPlayed == landsPlayed
                && e.stateHash == stateHash && e.phase.equals(phase)) {
            return e.mask;
        }
        List<SpellAbility> mask = buildPriorityOptions(game, player);
        e = new SeatEntry();
        e.timestamp = ts;
        e.turn = turn;
        e.phase = phase;
        e.stackSize = stackSize;
        e.landsPlayed = landsPlayed;
        e.stateHash = stateHash;
        e.mask = mask;
        perSeat.put(player, e);
        return mask;
    }

    private static List<SpellAbility> buildPriorityOptions(Game game, Player player) {
        List<SpellAbility> options = Lists.newArrayList();
        CardCollection cards = ComputerUtilCard.dedupeCards(ComputerUtilAbility.getAvailableCards(game, player));
        // getOriginalAndAltCostAbilities is the AI's own iteration set
        // (AiController.chooseSpellAbilityToPlay): it re-expands the
        // alternative/additional-cost variants that getSpellAbilities
        // collapses, so each variant gets its own payability check — a spell
        // payable ONLY via its alternative cost (e.g. Snuff Out's 4 life)
        // must appear as an option or the logged legality mask would forbid
        // the heuristic's own pick (found by the D2 smoke validator).
        for (SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(
                ComputerUtilAbility.getSpellAbilities(cards, player), player)) {
            if (!sa.isLandAbility() && sa.canPlay()
                    && (!PAYCHECK || ComputerUtilCost.canPayCost(sa, player, false))) {
                options.add(sa);
            }
        }
        CardCollectionView lands = ComputerUtilAbility.getAvailableLandsToPlay(game, player);
        if (lands != null) {
            for (Card land : lands) {
                for (SpellAbility sa : land.getAllPossibleAbilities(player, true)) {
                    if (sa.isLandAbility()) {
                        sa.setActivatingPlayer(player);
                        if (sa.canPlay()) {
                            options.add(sa);
                        }
                    }
                }
            }
        }
        return options;
    }
}
