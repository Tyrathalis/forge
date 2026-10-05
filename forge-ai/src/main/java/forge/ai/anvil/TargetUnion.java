package forge.ai.anvil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

/**
 * Anvil ADR-0122 (2026-10-04): the union target mask. Each priority option
 * carries the union of its legal targets over the targeting nodes of its
 * sub-ability chain — the same enumeration the out-of-cast target surface
 * uses (TargetRestrictions.getAllCandidates: players, then the cards of the
 * node's zones) plus the stack entries the node may target (getAllCandidates
 * never lists abilities on the stack; the engine's own count does). The
 * Python decoder masks its target pointer to the chosen option's set; the
 * realizer (CastPlanRealizer.tryApply) stays the backstop for what a union
 * over nodes cannot say — which node a ref belongs to, divided amounts,
 * cross-node restrictions.
 *
 * <p>Exactness rule: a node is enumerated with its own and its parents'
 * targets cleared, so every restriction that reads earlier picks (another
 * target, same controller, different CMC, total CMC caps, ...) passes — the
 * union OVER-approximates there, which the backstop absorbs. Where clearing
 * makes the engine UNDER-approximate (a predicate that needs a parent target
 * to exist, or a ValidTgts / count that reads X before X is paid) the option
 * is declared unmasked ("tg":null plus the reason) rather than given a wrong
 * set: a mask that excludes a legal target is the one failure the agreement
 * check must never see (the ADR-0005 lesson).
 *
 * <p>Record idiom (an additive per-option field; older stores read unmasked):
 * <pre>
 *   ,"tg":[{"e":id},{"pi":seat},{"e":hostId,"stk":1}],"tn":N[,"tz":1]   masked
 *   ,"tg":null,"tu":"&lt;reason&gt;"                                         unmasked
 * </pre>
 * "tn" = the chain's summed minimum target count; "tz" = some node with a
 * minimum of one or more has no candidate (the option cannot fit, whatever
 * the model picks). A non-targeting option carries "tg":[] (STOP alone is
 * legal: tryApply refuses any ref on it). No census row is written here —
 * Census.rec feeds the loop tripline, and this runs inside the option
 * writer on every logged window.
 *
 * <p>Reads state only (the enumeration swaps targets out and back and runs
 * under the scratch RNG); the heuristic's game path is untouched and the
 * record is not part of the forkcheck digest — the ADR-0025 forkcheck is
 * still the proof. {@code -Danvil.tgtmask=off} withholds the field.
 */
public final class TargetUnion {

    public static final boolean ENABLED = !"off".equals(System.getProperty("anvil.tgtmask", "on"));

    /** cmcLEX / powerLTX / cmcEQX ... or a bare X (TargetMin$ X): a value X
     *  the scan reads as 0 before the plan says what X is. */
    private static final Pattern X_REF = Pattern.compile(
            "(?:LE|LT|EQ|GE|GT)X(?![A-Za-z0-9])|(?<![A-Za-z0-9])X(?![A-Za-z0-9])");

    private TargetUnion() {
    }

    /** One option's enumeration. refs == null means unmasked (why says what). */
    public static final class Union {
        public final List<String> refs;
        public final int min;
        public final boolean unfit;
        public final String why;

        Union(List<String> refs, int min, boolean unfit, String why) {
            this.refs = refs;
            this.min = min;
            this.unfit = unfit;
            this.why = why;
        }

        static Union unmasked(String why) {
            return new Union(null, 0, false, why);
        }
    }

    /** Appends the option's mask fields to an opts entry (after "ak"). */
    public static void append(StringBuilder sb, Game g, Player p, SpellAbility sa) {
        Union u = of(g, p, sa);
        if (u.refs == null) {
            sb.append(",\"tg\":null,\"tu\":\"").append(u.why).append('"');
            return;
        }
        sb.append(",\"tg\":[");
        for (int i = 0; i < u.refs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(u.refs.get(i));
        }
        sb.append("],\"tn\":").append(u.min);
        if (u.unfit) {
            sb.append(",\"tz\":1");
        }
    }

    public static Union of(Game g, Player p, SpellAbility sa) {
        try {
            return AnvilOptions.withScratchRng(() -> enumerate(g, p, sa));
        } catch (Exception e) {
            return Union.unmasked("err");
        }
    }

    private static Union enumerate(Game g, Player p, SpellAbility sa) {
        SpellAbility root = Surfaces.unwrap(sa);
        if (root == null) {
            return Union.unmasked("null");
        }
        List<SpellAbility> nodes = new ArrayList<>(2);
        for (SpellAbility node = root; node != null; node = node.getSubAbility()) {
            if (node.usesTargeting()) {
                nodes.add(node);
            }
        }
        if (nodes.isEmpty()) {
            return new Union(new ArrayList<>(0), 0, false, null);
        }
        if (root.getActivatingPlayer() == null) {
            root.setActivatingPlayer(p); // trickles down the chain; the scan has set it already
        }
        boolean hasX = !root.isLandAbility() && root.getPayCosts() != null
                && root.getPayCosts().getTotalMana() != null
                && root.getPayCosts().getTotalMana().countX() > 0;
        for (SpellAbility node : nodes) {
            String why = unmaskable(node, hasX);
            if (why != null) {
                return Union.unmasked(why);
            }
        }
        // Clear every node's targets (a stale TargetChoices from an earlier AI
        // evaluation pass would feed the unique / same-controller reads), then
        // restore them — the enumeration must leave the option as it found it.
        TargetChoices[] saved = new TargetChoices[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            saved[i] = nodes.get(i).getTargets();
            nodes.get(i).resetTargets();
        }
        try {
            Set<String> refs = new LinkedHashSet<>();
            int min = 0;
            boolean unfit = false;
            List<Player> seats = g.getRegisteredPlayers();
            for (SpellAbility node : nodes) {
                TargetRestrictions tr = node.getTargetRestrictions();
                int hits = 0; // the node's own candidates, whether or not an earlier node listed them
                for (GameEntity ge : tr.getAllCandidates(node)) {
                    if (ge instanceof Player) {
                        refs.add("{\"pi\":" + seats.indexOf((Player) ge) + '}');
                        hits++;
                    } else if (ge instanceof Card) {
                        Card c = (Card) ge;
                        if (c.getZone() != null && c.getZone().getZoneType() == ZoneType.Stack) {
                            continue; // a spell on the stack joins as its stack entry below
                        }
                        refs.add("{\"e\":" + c.getId() + '}');
                        hits++;
                    }
                }
                if (tr.getZone() != null && tr.getZone().contains(ZoneType.Stack)) {
                    for (SpellAbilityStackInstance si : g.getStack()) {
                        SpellAbility top = si.getSpellAbility();
                        if (top == null || !node.canTargetSpellAbility(top)) {
                            continue;
                        }
                        Card h = si.getSourceCard() != null ? si.getSourceCard() : top.getHostCard();
                        if (h != null) {
                            refs.add("{\"e\":" + h.getId() + ",\"stk\":1}");
                            hits++;
                        }
                    }
                }
                int nodeMin = tr.getMinTargets(node.getHostCard(), node);
                min += Math.max(0, nodeMin);
                if (nodeMin >= 1 && hits == 0) {
                    unfit = true; // a required node with nothing to point at
                }
            }
            return new Union(new ArrayList<>(refs), min, unfit, null);
        } finally {
            for (int i = 0; i < nodes.size(); i++) {
                nodes.get(i).setTargets(saved[i]);
            }
        }
    }

    /** The reason this node cannot be enumerated faithfully at scan time, or
     *  null. Under-approximation classes only — over-approximation is the
     *  backstop's job. */
    static String unmaskable(SpellAbility node, boolean hasX) {
        if (node.hasParam("TargetsWithRelatedProperty")) {
            return "related"; // canTarget returns false until a parent target exists
        }
        if (node.hasParam("TargetingPlayerControls")) {
            return "tgtplayer"; // the targeting player is bound at cast time
        }
        for (String k : new String[] {"TargetsWithDefinedController", "TargetsWithSharedCardType"}) {
            String v = node.getParam(k);
            if (v != null && (v.contains("ParentTarget") || v.contains("Targeted"))) {
                return "parent"; // defined by a parent's pick, which is empty at scan time
            }
        }
        if (hasX) {
            TargetRestrictions tr = node.getTargetRestrictions();
            StringBuilder text = new StringBuilder();
            if (tr.getValidTgts() != null) {
                for (String s : tr.getValidTgts()) {
                    text.append(s).append(',');
                }
            }
            text.append(tr.getMinTargets()).append(',').append(tr.getMaxTargets());
            if (X_REF.matcher(text).find()) {
                return "x"; // the plan's X is unknown at scan time (read as 0)
            }
        }
        return null;
    }
}
