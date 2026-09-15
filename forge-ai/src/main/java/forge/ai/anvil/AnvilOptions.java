package forge.ai.anvil;

import com.google.common.collect.Lists;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
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
 * PAYCHECK note — payable by the executor's own predicate since M12 Build 0) plus legal land
 * drops. Pass is not an option here — callers represent it themselves
 * (index 0 on the bridge; a null answer in the log).
 */
public final class AnvilOptions {
    private AnvilOptions() {
    }

    /**
     * M12 Build 0 (ADR-0102): the logged option set is TIMING-LEGAL AND
     * PAYABLE candidates — the mask's legality predicate is the executor's
     * own apply-time predicate ({@link #payable}), so filter and adjudicator
     * agree by construction and the apply-time veto falls to the late-pricing
     * residual (cost reductions/additional costs that price only after
     * targets and X are set — the M1 D3 finding that turned the old filter
     * off: 11 expert picks excluded in 320 games; the residual is COUNTED,
     * never absorbed). -Danvil.scan.paycheck=off restores the M1–M11
     * timing-only basis for comparison runs. History: the pre-M12 doc said
     * "affordability is the model's to learn"; ADR-0101 finding 3 traced
     * 18–30% of cast attempts vetoed at apply to that choice.
     */
    private static final boolean PAYCHECK =
            !"off".equals(System.getProperty("anvil.scan.paycheck", "on"));

    /**
     * Shadow measurement for the Build 0 smoke (-Danvil.scan.payshadow=on):
     * every option the filter rejects is re-tested with the M9 legality-
     * derived enumerator; a rejected option the enumerator can pay is the
     * RESCUE class (chained-activation mana the auto-payer cannot see,
     * ADR-0065). One census row per window: n / rej / rescue. Census-only,
     * never a game-path change; off in production.
     */
    private static final boolean PAYSHADOW =
            "on".equals(System.getProperty("anvil.scan.payshadow", "off"));

    /**
     * M12 Build 3 evening 4 (ADR-0105; the ADR-0102 rescue class): with the
     * flag ON (AnvilRun -payrescue, or -Danvil.scan.payrescue=on) an option
     * the auto-payer's predicate rejects but the M9 enumerator can pay is
     * ADMITTED to the mask, the realizer's payability accepts it
     * (payableOrRescue), and its payment window pays DIRECTED by the
     * enumerator's first plan wherever auto cannot (PlayerControllerAnvil):
     * "admit payable || enumeratorPlan and pay directed when auto cannot".
     * Off (default) = byte-identical to before: a game-path change only
     * under the flag (the ADR-0025 proof runs with it off). Cost with the
     * flag on: the enumerator on every rejected option (+19% engine time
     * per game at the Build 0 smoke).
     */
    public static volatile boolean PAYRESCUE =
            "on".equals(System.getProperty("anvil.scan.payrescue", "off"));

    /** The realizer's payability under the rescue flag: the predicate, or
     *  (flag on) a feasible enumerator plan. */
    public static boolean payableOrRescue(Game game, Player player, SpellAbility sa) {
        return payable(game, player, sa) || (PAYRESCUE && withScratchRng(() -> shadowRescue(player, sa)));
    }

    /**
     * The legality subset of ComputerUtilCost.canPayCost (ADR-0102 item 1):
     * the extra-mana taxes (Nether Void class, command-zone effects), ward
     * mana when targets are set, ComputerUtilMana.canPayManaCost and the
     * additional-cost parts — and NONE of canPayCost's judgment calls: the
     * planeswalker-ultimate coin flip (an RNG draw — trajectory-perturbing
     * on the scan path), the ward willPayCosts, the Casualty
     * AIDontSacToCasualty filter. Shared by the scan (pre-targets, X unset
     * → X=0, optimistic) and the realizer (post-targets). Auto-payer-derived
     * by construction — chained-activation payability is the enumerator's
     * (the shadow counter above measures the gap).
     */
    public static boolean payable(Game game, Player player, SpellAbility sa) {
        return withScratchRng(() -> payableInner(game, player, sa));
    }

    private static boolean payableInner(Game game, Player player, SpellAbility sa) {
        if (sa.isLandAbility()) {
            return true;
        }
        if (sa.getActivatingPlayer() == null) {
            sa.setActivatingPlayer(player);
        }
        final forge.game.cost.Cost cost = sa.getPayCosts();
        if (cost == null) {
            return true;
        }
        int extraMana = 0;
        final boolean cannotBeCountered = !sa.isCounterableBy(null);
        if (sa instanceof forge.game.spellability.Spell) {
            for (Card c : game.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                final String snem = c.getSVar("AI_SpellsNeedExtraMana");
                if (snem == null || snem.isEmpty()) {
                    continue;
                }
                if (cannotBeCountered && c.getName().equals("Nether Void")) {
                    continue;
                }
                String[] parts = snem.split(" ");
                boolean meets = parts.length == 1
                        || player.isValid(parts[1], c.getController(), c, sa);
                if (meets && parts[0].chars().allMatch(Character::isDigit)) {
                    extraMana += Integer.parseInt(parts[0]);
                }
            }
            for (Card c : player.getCardsIn(forge.game.zone.ZoneType.Command)) {
                if (cannotBeCountered) {
                    continue;
                }
                final String snem = c.getSVar("SpellsNeedExtraManaEffect");
                if (snem != null && !snem.isEmpty() && snem.chars().allMatch(Character::isDigit)) {
                    extraMana += Integer.parseInt(snem);
                }
            }
        }
        if (!sa.isTrigger() && !cannotBeCountered) {
            java.util.Set<forge.game.GameObject> seen = new java.util.HashSet<>();
            for (forge.game.spellability.TargetChoices tc : sa.getAllTargetChoices()) {
                for (Card tgt : tc.getTargetCards()) {
                    if (!seen.add(tgt)) {
                        continue;
                    }
                    if (tgt.hasKeyword(forge.game.keyword.Keyword.WARD) && tgt.isInPlay()
                            && tgt.getController().isOpponentOf(sa.getHostCard().getController())) {
                        forge.game.cost.Cost ward = ComputerUtilCard.getTotalWardCost(tgt);
                        if (ward.hasManaCost()) {
                            extraMana += ward.getTotalMana().getCMC();
                        }
                    }
                }
            }
        }
        return forge.ai.ComputerUtilMana.canPayManaCost(cost, sa, player, extraMana, false)
                && forge.game.cost.CostPayment.canPayAdditionalCosts(cost, sa, false, player);
    }

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
    /** Shadow-only: can the M9 enumerator pay what the auto-payer cannot?
     *  Cost-modified spells are out of the enumerator's scope (spec §12b)
     *  and count as no-rescue; enumeration errors likewise (never throws). */
    private static boolean shadowRescue(Player player, SpellAbility sa) {
        try {
            final forge.game.cost.Cost cost = sa.getPayCosts();
            if (cost == null || !cost.hasManaCost() || PaymentEnumerator.costModified(sa)) {
                return false;
            }
            PaymentEnumerator.Result r = PaymentEnumerator.enumerate(player, sa, cost.getTotalMana());
            return r.planCount >= 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long seatStateHash(Player player) {
        long h = 1469598103934665603L;
        // Build 0 (ADR-0102): payability joined the mask, so its inputs the
        // timestamp does not version join the key — floating mana and life
        // (phyrexian shards, life-payment alt costs).
        h = h * 1099511628211L + player.getManaPool().totalMana();
        h = h * 1099511628211L + player.getLife();
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

    /**
     * The scan runs on a THROWAWAY RNG (M12 Build 0, ADR-0102 consequences,
     * found by the mask-cache obs-diff gate 2026-09-06): the payability test
     * walks ComputerUtilMana.isManaSourceReserved, whose MyRandom.percentTrue
     * draws from the game RNG on every shard × source it examines — so a
     * scan perturbed the trajectory a seed plays (the D2 "-obs perturbs"
     * finding, now explained) and any skipped scan (a cache hit) shifted the
     * stream. With the game's Random swapped out for the scan's duration
     * the scan is RNG-neutral by construction: obs logging, the mask cache,
     * the realizer's apply-time check and the search copies' scans all leave
     * the game's randomness exactly where they found it.
     */
    private static final long SCRATCH_SEED = 0x5CA4A11CEL;

    public static <T> T withScratchRng(java.util.function.Supplier<T> body) {
        final java.util.Random saved = forge.util.MyRandom.getRandom();
        forge.util.MyRandom.setRandom(new java.util.Random(SCRATCH_SEED));
        try {
            return body.get();
        } finally {
            forge.util.MyRandom.setRandom(saved);
        }
    }

    public static List<SpellAbility> priorityOptions(Game game, Player player) {
        return withScratchRng(() -> priorityOptionsInner(game, player));
    }

    private static List<SpellAbility> priorityOptionsInner(Game game, Player player) {
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
        int scanned = 0, rejected = 0, rescue = 0;
        // the mana-source memo (ComputerUtilMana, 2026-09-14): one grouping per scan
        forge.ai.ComputerUtilMana.armSourceMemo(player);
        try {
        for (SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(
                ComputerUtilAbility.getSpellAbilities(cards, player), player)) {
            if (sa.isLandAbility() || !sa.canPlay()) {
                continue;
            }
            scanned++;
            if (!PAYCHECK || payable(game, player, sa)) {
                options.add(sa);
                continue;
            }
            rejected++;
            if ((PAYSHADOW || PAYRESCUE) && shadowRescue(player, sa)) {
                rescue++;
                if (PAYRESCUE) {
                    options.add(sa); // evening 4: the rescue class admitted (pays directed)
                }
            }
        }
        } finally {
            forge.ai.ComputerUtilMana.disarmSourceMemo();
        }
        if (PAYSHADOW || PAYRESCUE) {
            Census.rec(game, player, "paymask", "n", scanned, "rej", rejected, "rescue", rescue,
                    "admitted", PAYRESCUE);
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
