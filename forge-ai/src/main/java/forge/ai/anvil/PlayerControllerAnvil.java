package forge.ai.anvil;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;

import java.util.List;
import java.util.Set;

/**
 * Anvil's controller (override plan, M0 form): the bridged tag set is answered
 * through an AnvilBridge; every other decision inherits the heuristic AI
 * (via CensusPlayerController, so when Census logging is open every callback
 * is recorded — bridged ones tagged by="bridge", the rest implicitly
 * heuristic-fallback; provenance rule of the override plan).
 *
 * Priority semantics (M0 random-legal): options are materialized engine-side
 * (legal-actions-only invariant) as pass + engine-legal, payable spell
 * abilities + legal land drops; the bridge picks an index. A picked spell is
 * then run through the AI's canPlaySa so targets/X are pre-set the AI-path
 * way (census finding: targets and X are injected, never callbacks); if the
 * AI evaluation vetoes it (no valid targets etc.), the window passes. So M0
 * plays "random over engine-legal, AI-targeted" — documented delta from pure
 * random-legal, revisited when CastPlan lands at M1.
 */
public class PlayerControllerAnvil extends CensusPlayerController {
    public static final String TAG_PRIORITY = "mtg.priority";
    public static final String TAG_MULLIGAN = "mtg.mulligan_keep";
    public static final String TAG_TUCK = "mtg.mulligan_tuck";
    public static final String TAG_TRIGGER = "mtg.trigger";
    public static final String TAG_BINARY = "mtg.binary";
    public static final String TAG_NUMBER = "mtg.number";
    public static final String TAG_ATTACK = "mtg.attack";   // M2 D5
    public static final String TAG_BLOCK = "mtg.block";     // M2 D5
    public static final String TAG_PAY_CLASS = "mtg.pay_mana_class"; // M9 D3 §3c

    private final AnvilBridge bridge;
    private final Set<String> bridgedTags;

    public PlayerControllerAnvil(Game game, Player p, LobbyPlayer lp, AnvilBridge bridge, Set<String> bridgedTags) {
        super(game, p, lp);
        this.bridge = bridge;
        this.bridgedTags = bridgedTags;
    }

    private boolean bridged(String tag) {
        return bridgedTags.contains(tag);
    }

    /** Does this seat answer priority over the bridge? (M7 forced-branch
     *  seat guard: both seats carry this controller class, but non-bridged
     *  seats have empty tag sets and play heuristic — forcing them would
     *  measure nothing.) */
    public boolean bridgesPriority() {
        return bridged(TAG_PRIORITY);
    }

    /**
     * D6 run-2 re-ask-on-veto (d6-vtrace-loop §6b): on an M1 CastPlan veto,
     * re-issue the priority decision with the vetoed candidate removed instead
     * of converting the window to a pass. Off = pre-amendment behavior.
     * Static because config is per-worker-JVM (AnvilRun sets it once from
     * -reask) and fork-created controllers must inherit it.
     */
    private static volatile boolean reaskOnVeto = false;
    /** Options shrink every re-ask, so termination is structural; the cap is
     *  insurance against pathologically wide windows re-vetoing in chains. */
    private static final int REASK_CAP = 8;

    public static void setReaskOnVeto(boolean v) {
        reaskOnVeto = v;
    }

    // ---- M7 forced-branch first decision (m7-plan D2) ----------------------
    // A one-shot directive on a fork copy: the drilled seat's FIRST
    // chooseSpellAbilityToPlay is forced to ACT (bridge ask with the pass
    // answer masked; §6b-style re-ask on veto with pass still masked) or HOLD
    // (pass without asking; play free afterwards). Keyed on Game identity
    // (the Obs stale-thread pattern): an abandoned hard-capped rollout thread
    // must never consume a directive armed for a later copy. WeakHashMap so
    // dead copies don't pin entries.

    public enum ForcedFirst { ACT, HOLD }

    /** Outcome of a consumed (or never-consumed) directive. CAST/HELD are the
     *  two branch-defining results; every SKIP_* drops the completion from
     *  pairing, loudly, with the reason in the labels row. */
    public enum ForcedResult {
        PENDING,            // armed, seat never asked (e.g. game ended first)
        CAST, HELD,
        SKIP_NO_OPTIONS,    // window had no castable candidates at all
        SKIP_EXHAUSTED,     // every candidate vetoed (or re-ask cap hit)
        SKIP_PASS_RESPONSE, // server answered pass despite the mask
        SKIP_NO_ONESHOT     // bridge lacks the composite path (M0 shape)
    }

    private static final class Forced {
        final ForcedFirst first;
        final String playerName;
        volatile ForcedResult result = ForcedResult.PENDING;

        Forced(ForcedFirst f, String p) {
            first = f;
            playerName = p;
        }
    }

    private static final java.util.Map<Game, Forced> forced =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void armForcedFirst(Game g, String playerName, ForcedFirst f) {
        forced.put(g, new Forced(f, playerName));
    }

    // ------------------------------------------------------------------
    // M7 D2 sequence probe (m7-plan routing pin, 2026-08-11): PERSISTENT
    // directive over an N-turn horizon — the sequence-granularity sibling
    // of the one-shot Forced above. HOLD = force-pass every bridged
    // priority cast window while turn <= untilTurn; ACT = forbid_decline
    // every window, mid-sequence exhaustion DEGRADES TO PASS (counted,
    // never a skip — a sequence arm cannot drop out mid-game the way a
    // one-shot branch can). Same Game-identity keying as Forced.

    public enum SeqMode { HOLD, ACT, OBSERVE }

    public static final class SeqDirective {
        public final SeqMode mode;
        final String playerName;
        final int untilTurn; // active while game turn <= untilTurn
        public volatile int holds = 0;    // windows force-passed (HOLD)
        public volatile int casts = 0;    // realized forced casts (ACT)
        public volatile int exhausts = 0; // ACT windows degraded to pass
        // First realized cast of the completion, as the candidate-label
        // SA string (Census.str — the model's sa_vocab basis). ADR-0054:
        // the sequence-contrastive target rewards the EVALUATED cast, so
        // the labels row must say which cast the act arm actually led with.
        public volatile String firstCastSa = null;
        // M8 D1 (m8-plan): OBSERVE mode never forces — it records the
        // seat's natural timing. First realized SPELL cast (isSpell();
        // lands and activated abilities — fetch cracks, equips — are mana
        // development, not the spell-timing axis, and would pull
        // completions into the in-window bin) + its absolute game turn,
        // and the first land-play turn as the confound check. -1 = never.
        public volatile String firstSpellSa = null;
        public volatile int firstSpellTurn = -1;
        public volatile int firstLandTurn = -1;

        SeqDirective(SeqMode m, String p, int u) {
            mode = m;
            playerName = p;
            untilTurn = u;
        }
    }

    private static final java.util.Map<Game, SeqDirective> seq =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void armSeq(Game g, String playerName, SeqMode m, int untilTurn) {
        seq.put(g, new SeqDirective(m, playerName, untilTurn));
    }

    /** Null when unarmed; counters live on the returned object. */
    public static SeqDirective seqDirective(Game g) {
        return seq.get(g);
    }

    public static void clearSeq(Game g) {
        seq.remove(g);
    }

    /** The seat's active sequence directive for the current window, or null
     *  (unarmed / other seat / horizon expired). */
    private SeqDirective activeSeq() {
        SeqDirective sd = seq.get(getGame());
        if (sd == null || !sd.playerName.equals(player.getName())
                || getGame().getPhaseHandler().getTurn() > sd.untilTurn) {
            return null;
        }
        return sd;
    }

    /** PENDING if armed but never consumed; null-safe (PENDING when unarmed —
     *  callers only read games they armed). */
    public static ForcedResult forcedResult(Game g) {
        Forced f = forced.get(g);
        return f == null ? ForcedResult.PENDING : f.result;
    }

    public static void clearForced(Game g) {
        forced.remove(g);
    }

    /** Consume the directive iff it targets this seat and is still pending.
     *  Non-matching seat leaves it armed (defensive: the first ask should
     *  always be the drilled seat — GameCopier resumes at its priority). */
    private Forced consumeForced() {
        Forced f = forced.get(getGame());
        if (f == null || f.result != ForcedResult.PENDING
                || !f.playerName.equals(player.getName())) {
            return null;
        }
        return f;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (!bridged(TAG_PRIORITY)) {
            return super.chooseSpellAbilityToPlay();
        }
        // Mutable copy: re-ask removes vetoed candidates between attempts.
        List<SpellAbility> options =
                Lists.newArrayList(AnvilOptions.priorityOptions(getGame(), player));

        Forced fd = consumeForced();
        if (fd != null && fd.first == ForcedFirst.HOLD) {
            // Forced hold: pass this window without asking; free afterwards.
            fd.result = ForcedResult.HELD;
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "forced", "pick", "pass");
            return null;
        }
        boolean forcedAct = fd != null; // fd.first == ACT
        if (forcedAct && options.isEmpty()) {
            fd.result = ForcedResult.SKIP_NO_OPTIONS;
            return null;
        }

        // Sequence directive (persistent, N-turn horizon). One-shot Forced
        // takes precedence if both are somehow armed (they never are).
        SeqDirective sd = fd == null ? activeSeq() : null;
        if (sd != null && sd.mode == SeqMode.HOLD) {
            sd.holds++;
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "seq", "pick", "pass");
            return null;
        }
        boolean seqAct = sd != null && sd.mode == SeqMode.ACT;
        if (seqAct && options.isEmpty()) {
            sd.exhausts++; // nothing to force this window; arm plays on
            return null;
        }

        for (int attempt = 0;; attempt++) {
            // Index 0 = pass; one round-trip per attempt.
            List<String> labels = Lists.newArrayListWithCapacity(options.size() + 1);
            labels.add("pass");
            for (SpellAbility sa : options) {
                labels.add(Census.str(sa));
            }
            // Structured-opts dec (same basis as the corpus label path) so D8
            // eval games are analyzable trajectories and ret() can label "oi".
            // A re-ask mints a fresh seq; the vetoed dec's ret(null) has
            // already run, so the single-slot oi bookkeeping is clear.
            long obsSeq = Obs.decPriority(getGame(), getPlayer(), "bridge", options);

            // M1 one-shot: the composite CastPlan path; null = M0-shape bridge.
            CastPlanAnswer plan = bridge.priorityCastPlan(TAG_PRIORITY, labels,
                    Obs.lastDecForBridge(getGame()), attempt, forcedAct || seqAct);
            if (plan != null) {
                OneShot r = oneShotCast(options, plan, obsSeq, attempt);
                if (r.sas != null || r.vetoedOption > 0) {
                    // Any cast ATTEMPT (realized or vetoed) ran canPlaySa on
                    // option SAs (targets/X mutation) — the seat's cached
                    // mask must not survive into the next window. A realized
                    // cast would invalidate via the timestamp anyway; the
                    // veto-then-pass case is the one that would not.
                    AnvilOptions.invalidate(getGame(), player);
                }
                if (r.vetoedOption <= 0) {
                    if (forcedAct) {
                        // sas null under the mask = server passed anyway
                        // (candidate-set mismatch or transport-failure PASS);
                        // the pair drops, loudly.
                        fd.result = r.sas != null ? ForcedResult.CAST
                                : ForcedResult.SKIP_PASS_RESPONSE;
                    } else if (seqAct) {
                        if (r.sas != null) {
                            sd.casts++;
                            if (sd.firstCastSa == null && !r.sas.isEmpty()) {
                                sd.firstCastSa = Census.str(r.sas.get(0));
                            }
                        } else {
                            sd.exhausts++; // server passed despite the mask
                        }
                    } else if (sd != null && sd.mode == SeqMode.OBSERVE
                            && r.sas != null) {
                        // M8 D1: pure recording — the ask above ran exactly
                        // as unarmed natural (forced flag false, no re-ask
                        // semantics change), so counters stay untouched.
                        int t = getGame().getPhaseHandler().getTurn();
                        for (SpellAbility s : r.sas) {
                            if (s.isLandAbility()) {
                                if (sd.firstLandTurn < 0) {
                                    sd.firstLandTurn = t;
                                }
                            } else if (s.isSpell() && sd.firstSpellSa == null) {
                                sd.firstSpellSa = Census.str(s);
                                sd.firstSpellTurn = t;
                            }
                        }
                    }
                    return r.sas; // realized cast, model pass, or oor pass
                }
                if (forcedAct || seqAct) {
                    // Forced/seq act re-asks on veto regardless of the global
                    // §6b flag: the arm is DEFINED as the best realizable
                    // cast under the mask. Exhaustion: one-shot = skip
                    // (pair drops); sequence = degrade to pass, counted.
                    if (attempt + 1 >= REASK_CAP) {
                        if (forcedAct) {
                            fd.result = ForcedResult.SKIP_EXHAUSTED;
                        } else {
                            sd.exhausts++;
                        }
                        return null;
                    }
                } else if (!reaskOnVeto || attempt + 1 >= REASK_CAP) {
                    return null; // pre-amendment behavior: veto = pass
                }
                SpellAbility vetoed = options.get(r.vetoedOption - 1);
                if (plan.hostLevel && vetoed.getHostCard() != null) {
                    // Host-level plans exhausted the host's whole ladder.
                    final Card host = vetoed.getHostCard();
                    options.removeIf(sa -> sa.getHostCard() == host);
                } else {
                    options.remove(r.vetoedOption - 1);
                }
                if (options.isEmpty()) {
                    if (forcedAct) {
                        fd.result = ForcedResult.SKIP_EXHAUSTED;
                    } else if (seqAct) {
                        sd.exhausts++;
                    }
                    return null; // only pass remains; nothing left to ask
                }
                continue;
            }
            if (forcedAct) {
                // M0-shape bridge can't honor the mask; skip, drop the pair.
                fd.result = ForcedResult.SKIP_NO_ONESHOT;
            } else if (seqAct) {
                sd.exhausts++; // M0-shape bridge can't honor the mask
                return null;
            }
            return selectOnePick(options, labels, obsSeq);
        }
    }

    /** M0 selectOne path (never re-asks; heuristic canPlaySa veto = pass). */
    private List<SpellAbility> selectOnePick(List<SpellAbility> options, List<String> labels,
            long obsSeq) {
        int pick = bridge.selectOne(TAG_PRIORITY, labels);
        if (pick == 0) {
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", "pass");
            Obs.ret(getGame(), obsSeq, null);
            return null;
        }
        SpellAbility chosen = options.get(pick - 1);
        // Non-pass pick: canPlaySa below mutates the chosen SA either way.
        AnvilOptions.invalidate(getGame(), player);
        if (!chosen.isLandAbility() && getAi().canPlaySa(chosen) != AiPlayDecision.WillPlay) {
            // Targets/X could not be set up; window passes. Counted so the
            // veto rate is visible (it biases the pick toward AI-playable).
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", Census.str(chosen), "veto", true);
            Obs.ret(getGame(), obsSeq, null);
            return null;
        }
        Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                "by", "bridge", "options", options.size(), "pick", Census.str(chosen));
        Obs.ret(getGame(), obsSeq, chosen);
        return Lists.newArrayList(chosen);
    }

    /** One-shot attempt outcome: sas = the answer (null = window passes);
     *  vetoedOption = the 1-based option index the realizer vetoed (0 = no
     *  veto — the caller only re-asks on a veto). */
    private static final class OneShot {
        final List<SpellAbility> sas;
        final int vetoedOption;

        OneShot(List<SpellAbility> sas, int vetoedOption) {
            this.sas = sas;
            this.vetoedOption = vetoedOption;
        }
    }

    /**
     * M1 D8: realize a composite CastPlan answer. The realizer adjudicates
     * legality only (never the heuristic's judgment — the M0 65% veto class);
     * a veto passes the window (or re-asks, D6 run-2), with the reason in the
     * census/provenance log. Census lines gain "reask"=attempt on re-asked
     * attempts (attempt > 0), so a success line with reask>0 = a rescue.
     */
    private OneShot oneShotCast(List<SpellAbility> options, CastPlanAnswer plan,
            long obsSeq, int attempt) {
        if (plan.optionIndex <= 0 || plan.optionIndex > options.size()) {
            boolean oor = plan.optionIndex != 0;
            if (attempt > 0) {
                Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                        "by", "bridge", "options", options.size(), "pick", "pass",
                        "oneshot", true, "oor", oor, "reask", attempt);
            } else {
                Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                        "by", "bridge", "options", options.size(), "pick", "pass",
                        "oneshot", true, "oor", oor);
            }
            Obs.ret(getGame(), obsSeq, null);
            return new OneShot(null, 0);
        }
        SpellAbility picked = options.get(plan.optionIndex - 1);
        List<SpellAbility> hostSas;
        if (plan.hostLevel && picked.getHostCard() != null) {
            hostSas = Lists.newArrayListWithCapacity(2);
            for (SpellAbility sa : options) {
                if (sa.getHostCard() == picked.getHostCard()) {
                    hostSas.add(sa);
                }
            }
        } else {
            hostSas = Lists.newArrayList(picked);
        }
        CastPlanRealizer.Result r = CastPlanRealizer.realize(getGame(), player, hostSas, plan);
        if (r.sa == null) {
            if (attempt > 0) {
                Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                        "by", "bridge", "options", options.size(), "pick", Census.str(picked),
                        "oneshot", true, "veto", r.veto, "hostSas", r.hostSas, "fits", r.fitCount,
                        "reask", attempt);
            } else {
                Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                        "by", "bridge", "options", options.size(), "pick", Census.str(picked),
                        "oneshot", true, "veto", r.veto, "hostSas", r.hostSas, "fits", r.fitCount);
            }
            Obs.ret(getGame(), obsSeq, null);
            return new OneShot(null, plan.optionIndex);
        }
        if (attempt > 0) {
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", Census.str(r.sa),
                    "oneshot", true, "rung", r.rung, "hostSas", r.hostSas, "fits", r.fitCount,
                    "divided", r.divided, "reask", attempt);
        } else {
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", Census.str(r.sa),
                    "oneshot", true, "rung", r.rung, "hostSas", r.hostSas, "fits", r.fitCount,
                    "divided", r.divided);
        }
        Obs.ret(getGame(), obsSeq, Lists.newArrayList(r.sa));
        return new OneShot(Lists.newArrayList(r.sa), 0);
    }

    /** London mulligans are rules-unbounded (hand redraws to 7 every time), so
     *  a pathological bridge answer loops the game forever at turn 0 (D8
     *  smoke 1). Insurance cap, far beyond any sane line. */
    private static final int MULLIGAN_CAP = 12;
    private int mulligansAsked;

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (!bridged(TAG_MULLIGAN)) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "mulliganKeepHand", null);
        boolean keep = bridge.bool(TAG_MULLIGAN);
        if (!keep && ++mulligansAsked >= MULLIGAN_CAP) {
            keep = true;
            Census.rec(getGame(), getPlayer(), "mulliganKeepHand", "by", "bridge",
                    "keep", true, "mull_cap", true);
        } else {
            Census.rec(getGame(), getPlayer(), "mulliganKeepHand", "by", "bridge", "keep", keep);
        }
        Obs.ret(getGame(), obsSeq, keep);
        return keep;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (!bridged(TAG_TUCK)) {
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        }
        List<String> handLabels = Lists.newArrayListWithCapacity(hand.size());
        for (Card c : hand) {
            handLabels.add(Census.str(c));
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "tuckCardsViaMulligan", handLabels);
        int[] picks = bridge.selectK(TAG_TUCK, hand.size(), cardsToReturn);
        CardCollection tuck = new CardCollection();
        for (int i : picks) {
            tuck.add(hand.get(i));
        }
        Census.rec(getGame(), getPlayer(), "tuckCardsViaMulligan", "by", "bridge", "n", tuck.size());
        Obs.ret(getGame(), obsSeq, tuck);
        return tuck;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        if (!bridged(TAG_TRIGGER)) {
            return super.confirmTrigger(sa);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "confirmTrigger", null, "sa", Census.str(sa));
        boolean yes = bridge.bool(TAG_TRIGGER);
        Census.rec(getGame(), getPlayer(), "confirmTrigger", "by", "bridge", "yes", yes);
        Obs.ret(getGame(), obsSeq, yes);
        return yes;
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (isMandatory || !bridged(TAG_TRIGGER)) {
            return super.playTrigger(host, wrapperAbility, isMandatory);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "playTrigger", null,
                "host", Census.str(host), "wrapperAbility", Census.str(wrapperAbility));
        boolean yes = bridge.bool(TAG_TRIGGER);
        Census.rec(getGame(), getPlayer(), "playTrigger", "by", "bridge", "yes", yes);
        Obs.ret(getGame(), obsSeq, yes);
        return yes && super.playTrigger(host, wrapperAbility, true);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        if (!bridged(TAG_BINARY)) {
            return super.chooseBinary(sa, question, kindOfChoice, defaultVal);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "chooseBinary", null,
                "question", question, "kind", String.valueOf(kindOfChoice));
        boolean v = bridge.bool(TAG_BINARY);
        Census.rec(getGame(), getPlayer(), "chooseBinary", "by", "bridge", "v", v);
        Obs.ret(getGame(), obsSeq, v);
        return v;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (!bridged(TAG_NUMBER)) {
            return super.chooseNumber(sa, title, min, max);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "chooseNumber", null,
                "title", title, "min", min, "max", max);
        int v = bridge.intInRange(TAG_NUMBER, min, max);
        Census.rec(getGame(), getPlayer(), "chooseNumber", "by", "bridge", "v", v);
        Obs.ret(getGame(), obsSeq, v);
        return v;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        if (!bridged(TAG_NUMBER)) {
            return super.chooseNumber(sa, title, values, relatedPlayer);
        }
        List<String> valueLabels = Lists.newArrayListWithCapacity(values.size());
        for (Integer n : values) {
            valueLabels.add(String.valueOf(n));
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "chooseNumber", valueLabels, "title", title);
        int v = values.get(bridge.selectOne(TAG_NUMBER, valueLabels));
        Census.rec(getGame(), getPlayer(), "chooseNumber", "by", "bridge", "v", v);
        Obs.ret(getGame(), obsSeq, v);
        return v;
    }

    /**
     * M9 D3 (§3c): conscious mana payment (m9-payment-surface-spec.md).
     * In-scope windows (effect=false, nonzero mana) run legality-derived
     * class enumeration; consequential windows (≥2 classes) bridge as
     * SELECT_ONE over {auto} ∪ classes on tag mtg.pay_mana_class — auto is
     * option 0, so a server that always answers 0 (or fallback/echo) is
     * bit-identical to today. A class answer executes float-then-apply
     * (PaymentEnumerator.executeDirected, the ADR-0065 primitive) and the
     * heuristic path completes the payment from the float (pool-first).
     * Failure semantics per spec §7: directed_salvage / directed_fail are
     * census reason codes, NEVER vetoes — D5's mechanism read must not
     * measure itself. Auto/heuristic payment goes through ComputerUtilMana
     * directly (calling super would double-log the census record).
     */
    @Override
    public boolean payManaCost(forge.card.mana.ManaCost toPay, forge.game.cost.CostPartMana costPartMana,
            SpellAbility sa, String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
        if (!bridged(TAG_PAY_CLASS) || effect || toPay == null || toPay.isZero()) {
            return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
        }
        final PaymentEnumerator.Result r;
        final boolean conseq;
        try {
            r = PaymentEnumerator.enumerate(getPlayer(), sa, toPay);
            conseq = PaymentEnumerator.consequential(r, getPlayer(), sa, toPay, effect);
        } catch (Exception e) {
            // enumeration must never kill the game thread: fall back to
            // today's behavior (auto), loudly reason-coded — never a veto.
            // Mirrors the non-consequential path (super would double-record).
            Census.rec(getGame(), getPlayer(), "payManaCost", "by", "auto",
                    "sa", Census.str(sa), "effect", false,
                    "enumerr", e.getClass().getSimpleName());
            long s2 = Obs.dec(getGame(), getPlayer(), "payManaCost",
                    "sa", Census.str(sa), "effect", false);
            boolean paid = autoPay(toPay, sa, effect);
            Obs.ret(getGame(), s2, paid);
            return paid;
        }
        final boolean forced = conseq && r.classes.size() == 1; // auto-unpayable, one class
        if (!conseq) {
            // non-consequential windows never bridge (the sparsity contract);
            // the flag telemetry still lands on the census record.
            Census.rec(getGame(), getPlayer(), "payManaCost", "by", "auto",
                    "sa", Census.str(sa), "effect", false,
                    "classes", r.classes.size(), "conseq", false, "trunc", r.truncated);
            long s = Obs.dec(getGame(), getPlayer(), "payManaCost",
                    "sa", Census.str(sa), "effect", false);
            boolean paid = autoPay(toPay, sa, effect);
            Obs.ret(getGame(), s, paid);
            return paid;
        }
        final List<String> labels = paymentOptionLabels(r);
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "payManaCost", labels,
                "sa", Census.str(sa), "cost", String.valueOf(toPay), "effect", false,
                "fpool", floatingPool(), "classes", r.classes.size(),
                "trunc", r.truncated, "forced", forced);
        int pick = bridge.selectOne(TAG_PAY_CLASS, labels);
        if (pick <= 0 || pick > r.classes.size()) {
            boolean paid = autoPay(toPay, sa, effect);
            Census.rec(getGame(), getPlayer(), "payManaCost", "by", "bridge",
                    "options", labels.size(), "pick", "auto", "paid", paid,
                    "classes", r.classes.size(), "conseq", true, "trunc", r.truncated,
                    "forced", forced);
            Obs.ret(getGame(), obsSeq, "auto:" + paid);
            return paid;
        }
        final PaymentEnumerator.PaymentClass pc = r.classes.get(pick - 1);
        final PaymentEnumerator.ExecOutcome out = PaymentEnumerator.executeDirected(getPlayer(), pc);
        boolean paid = autoPay(toPay, sa, effect); // completes from the float, pool-first
        int residue = getPlayer().getManaPool().totalMana();
        String exec = !paid ? "directed_fail"
                : out == PaymentEnumerator.ExecOutcome.DIRECTED_OK ? "directed_ok" : "directed_salvage";
        Census.rec(getGame(), getPlayer(), "payManaCost", "by", "bridge",
                "options", labels.size(), "pick", pick, "exec", exec, "paid", paid,
                "float_residue", residue,
                "classes", r.classes.size(), "conseq", true, "trunc", r.truncated,
                "forced", forced);
        Obs.ret(getGame(), obsSeq, exec);
        return paid;
    }

    /** Current floating pool by mana type (spec §6 window context: WUBRGC). */
    private String floatingPool() {
        StringBuilder sb = new StringBuilder(12);
        for (byte t : forge.card.mana.ManaAtom.MANATYPES) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(getPlayer().getManaPool().getAmountOfColor(t));
        }
        return sb.toString();
    }

    /** The PlayerControllerAi payment body, called directly — super would
     *  re-record the census window. */
    private boolean autoPay(forge.card.mana.ManaCost toPay, SpellAbility sa, boolean effect) {
        return forge.ai.ComputerUtilMana.payManaCost(
                new forge.game.cost.Cost(toPay, effect), getPlayer(), sa, effect);
    }

    /** Wire option labels (spec §5): option 0 = auto; classes carry the
     *  entity refs of the representative plan (the pointer-head substrate),
     *  pool spend by type, and phyrexian life count. */
    private static List<String> paymentOptionLabels(PaymentEnumerator.Result r) {
        List<String> labels = Lists.newArrayListWithCapacity(r.classes.size() + 1);
        labels.add("{\"auto\":true}");
        for (PaymentEnumerator.PaymentClass pc : r.classes) {
            StringBuilder sb = new StringBuilder(64);
            sb.append("{\"ents\":[");
            boolean first = true;
            for (PaymentEnumerator.Atom a : pc.atoms) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(a.host.getId());
                first = false;
            }
            sb.append("],\"pool\":[");
            for (int i = 0; i < pc.poolSpend.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(pc.poolSpend[i]);
            }
            sb.append("],\"phy\":").append(pc.phyrexianLife).append('}');
            labels.add(sb.toString());
        }
        return labels;
    }

    /**
     * M2 D5 combat declarations. Labels are obs-join (post-declaration windows
     * carry the atk/blk flags), so no Obs.ret — same record shape as the
     * heuristic corpus. The realizer is engine-legality-only with
     * requirements repair (CombatRealizer); every deviation from the model's
     * raw map is census-counted (applied/dropped/forced/fallback). If the
     * bridge lacks the shape (misconfigured non-model arm), the AI brains
     * answer directly — super would double-log the dec.
     */
    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        if (!bridged(TAG_ATTACK)) {
            super.declareAttackers(attacker, combat);
            return;
        }
        Obs.decBridged(getGame(), getPlayer(), "declareAttackers", null,
                "attacker", Census.str(attacker));
        CombatMapAnswer ans = bridge.attackMap(TAG_ATTACK, Obs.lastDecForBridge(getGame()));
        if (ans == null) {
            Census.rec(getGame(), getPlayer(), "declareAttackers", "by", "bridge",
                    "noShape", true);
            getAi().declareAttackers(attacker, combat);
            return;
        }
        CombatRealizer.Result r = CombatRealizer.realizeAttack(
                getGame(), attacker, combat, getAi(), ans);
        Census.rec(getGame(), getPlayer(), "declareAttackers", "by", "bridge",
                "assign", ans.assignments.size(), "applied", r.applied,
                "dropped", r.dropped, "forced", r.forced, "fallback", r.fallback);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        if (!bridged(TAG_BLOCK)) {
            super.declareBlockers(defender, combat);
            return;
        }
        Obs.decBridged(getGame(), getPlayer(), "declareBlockers", null,
                "defender", Census.str(defender));
        CombatMapAnswer ans = bridge.blockMap(TAG_BLOCK, Obs.lastDecForBridge(getGame()));
        if (ans == null) {
            Census.rec(getGame(), getPlayer(), "declareBlockers", "by", "bridge",
                    "noShape", true);
            getAi().declareBlockersFor(defender, combat);
            return;
        }
        CombatRealizer.Result r = CombatRealizer.realizeBlock(
                getGame(), defender, combat, ans);
        Census.rec(getGame(), getPlayer(), "declareBlockers", "by", "bridge",
                "assign", ans.assignments.size(), "applied", r.applied,
                "dropped", r.dropped, "forced", r.forced, "fallback", r.fallback);
    }
}
