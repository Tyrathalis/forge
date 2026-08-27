package forge.ai.anvil;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

import forge.ai.AiCostDecision;
import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.collect.FCollectionView;

/**
 * Anvil M11-routing ceiling probes (m11-routing-probes-spec.md): the
 * -forcechoice directive. Armed per fork copy by the AnvilRun choice-
 * rollout mode, keyed on Game identity in a WeakHashMap (the
 * ScheduleDirective idiom — an abandoned hard-capped thread must never
 * consume a directive armed for a later copy).
 *
 * Two kinds, one directive class:
 *
 *   TUTOR   — probe T: at the target seat's FIRST family-matching
 *             SELECT_ONE window on the target turn
 *             (chooseSingleEntityForEffect / chooseSingleCardForZoneChange
 *             whose sa/prompt/title matches the tutor/dig family), force
 *             the candidate at the directive's 0-based index. The family
 *             regex mirrors scripts/m11_mining.py — the mining rung's
 *             classifier IS the run-time filter, so the forced universe
 *             equals the mined universe by construction. Index out of
 *             bounds = fired-with-miss ("idx_oob"), natural play.
 *
 *   PREVENT — probe P: at the target seat's FIRST payCostToPreventEffect
 *             window on the target turn, force PAY (the
 *             PlayerControllerAi payment path with the willingness
 *             heuristic bypassed; unaffordable = fired-with-miss
 *             "pay_unaffordable", returns false) or force DECLINE
 *             (return false without paying).
 *
 * First-match semantics: the directive fires at most once; later family
 * windows on the turn play natural (multi-window turns are counted by
 * windowsSeen — the reader's coverage instrument). All failures are
 * counters/reason codes, never exceptions into the game thread.
 */
public final class ChoiceDirective {

    public static final int KIND_TUTOR = 0;
    public static final int KIND_PREVENT = 1;
    /** PREVENT actions. TUTOR action = 0-based candidate index. */
    public static final int ACT_DECLINE = 0;
    public static final int ACT_PAY = 1;

    /** Mirrors scripts/m11_mining.py SEARCH_RX + DIG_RX — one alternation. */
    private static final Pattern FAMILY = Pattern.compile(
            "[Ss]earch (?:your|their|his|her) librar|[Ll]ook at the top");

    // ---- job spec (immutable; kind/action public for the runner's row) --
    final String playerName;
    final int turn;
    public final int kind;
    public final int action;

    // ---- trace (game thread writes, runner reads after completion) -----
    public volatile boolean fired = false;
    /** Fired-with-miss reason (idx_oob / pay_unaffordable); null = clean. */
    public volatile String miss = null;
    /** Family-matching windows seen for the target seat on the target
     *  turn (fired or not) — the multi-window coverage instrument. */
    public volatile int windowsSeen = 0;
    /** Forced candidate name (TUTOR) or "pay"/"decline" (PREVENT). */
    public volatile String chosen = null;
    /** Candidate count at the fired window; -1 = never fired. */
    public volatile int ncand = -1;
    /** PREVENT force-pay: the CostPayment completed. */
    public volatile boolean payOk = false;

    private ChoiceDirective(String playerName, int turn, int kind, int action) {
        this.playerName = playerName;
        this.turn = turn;
        this.kind = kind;
        this.action = action;
    }

    private static final Map<Game, ChoiceDirective> armed =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static ChoiceDirective arm(Game g, String playerName, int turn,
            int kind, int action) {
        ChoiceDirective d = new ChoiceDirective(playerName, turn, kind, action);
        armed.put(g, d);
        return d;
    }

    /** Null when unarmed; trace fields live on the returned object. */
    public static ChoiceDirective directive(Game g) {
        return armed.get(g);
    }

    public static void clear(Game g) {
        armed.remove(g);
    }

    /** The live directive for this window, or null (unarmed / other seat /
     *  wrong turn / wrong kind). Fired directives still MATCH so the
     *  windowsSeen counter keeps counting; callers check fired. */
    private static ChoiceDirective match(Game g, Player p, int kind) {
        final ChoiceDirective d = armed.get(g);
        if (d == null || d.kind != kind || !d.playerName.equals(p.getName())) {
            return null;
        }
        try {
            if (g.getPhaseHandler().getTurn() != d.turn) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return d;
    }

    private static boolean family(SpellAbility sa, String prompt) {
        final StringBuilder sb = new StringBuilder(96);
        final String s = Census.str(sa);
        if (s != null) {
            sb.append(s);
        }
        if (prompt != null) {
            sb.append(' ').append(prompt);
        }
        return FAMILY.matcher(sb).find();
    }

    // ------------------------------------------------------------------
    // Hooks (called from the generated CensusPlayerController wrappers;
    // null return = no force, fall through to super)

    /** chooseSingleEntityForEffect: force optionList[index] at the first
     *  family window. Never throws. */
    public static <T extends GameEntity> T forceEntity(Game g, Player p,
            FCollectionView<T> optionList, SpellAbility sa, String title) {
        try {
            final ChoiceDirective d = match(g, p, KIND_TUTOR);
            if (d == null || !family(sa, title)) {
                return null;
            }
            d.windowsSeen++;
            if (d.fired) {
                return null;
            }
            return d.fireIndex(optionList, sa);
        } catch (Exception e) {
            return null;
        }
    }

    /** chooseSingleCardForZoneChange: force fetchList[index] at the first
     *  family window. Never throws. */
    public static Card forceZoneChange(Game g, Player p, CardCollection fetchList,
            SpellAbility sa, String selectPrompt) {
        try {
            final ChoiceDirective d = match(g, p, KIND_TUTOR);
            if (d == null || !family(sa, selectPrompt)) {
                return null;
            }
            d.windowsSeen++;
            if (d.fired) {
                return null;
            }
            return d.fireIndex(fetchList, sa);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T fireIndex(Iterable<T> options, SpellAbility sa) {
        fired = true;
        int n = Census.sz(options);
        ncand = n;
        if (action < 0 || action >= n) {
            miss = "idx_oob";
            return null;
        }
        int i = 0;
        for (T opt : options) {
            if (i++ == action) {
                chosen = opt instanceof GameEntity
                        ? ((GameEntity) opt).getName() : Census.str(opt);
                return opt;
            }
        }
        miss = "idx_oob";
        return null;
    }

    /** payCostToPreventEffect: force pay/decline at the first window.
     *  Null = no force (natural). Never throws. */
    public static Boolean forcePrevent(Game g, Player p, Cost cost, SpellAbility sa) {
        try {
            final ChoiceDirective d = match(g, p, KIND_PREVENT);
            if (d == null) {
                return null;
            }
            d.windowsSeen++;
            if (d.fired) {
                return null;
            }
            d.fired = true;
            if (d.action == ACT_DECLINE) {
                d.chosen = "decline";
                return Boolean.FALSE;
            }
            d.chosen = "pay";
            if (!ComputerUtilCost.canPayCost(cost, sa, p, true)) {
                d.miss = "pay_unaffordable";
                return Boolean.FALSE;
            }
            final CostPayment pay = new CostPayment(cost, sa);
            d.payOk = pay.payComputerCosts(new AiCostDecision(p, sa, true));
            return d.payOk;
        } catch (Exception e) {
            return null;
        }
    }

    /** Enriched census record for payCostToPreventEffect (the mining
     *  rung's attribution gap: 58% of windows had an empty sa string) —
     *  host card + api ride the standard kvs. Routed here by the
     *  generator's REC_OVERRIDES so the generated file stays logic-free. */
    public static void recPrevent(Game g, Player p, Cost cost, SpellAbility sa,
            boolean alreadyPaid, FCollectionView<Player> allPayers) {
        String src = null;
        String api = null;
        try {
            if (sa != null && sa.getHostCard() != null) {
                src = sa.getHostCard().getName();
            }
            if (sa != null && sa.getApi() != null) {
                api = sa.getApi().toString();
            }
        } catch (Exception ignored) {
        }
        Census.rec(g, p, "payCostToPreventEffect", "sa", Census.str(sa),
                "src", src, "api", api,
                "alreadyPaid", alreadyPaid, "allPayers", Census.sz(allPayers));
    }
}
