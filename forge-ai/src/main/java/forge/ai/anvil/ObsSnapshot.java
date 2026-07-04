package forge.ai.anvil;

import com.google.common.collect.Multiset;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity-level observation of a Game, appended as one JSON object
 * (docs/design/observation-schema-v1.md). Full state with per-entity
 * visibility deviations; the information set is enforced by the Python
 * transform, never here (M2's belief head trains on what a perspective
 * filter would discard).
 *
 * Libraries are deliberately not walked (schema decision 5): remaining-library
 * counts are derivable transform-side from decklist minus observed owned,
 * non-token entities. Absent = false/0/null throughout.
 */
final class ObsSnapshot {
    /** Walk order; also the sort key so record bytes are replay-stable. */
    private static final ZoneType[] ZONES = {
            ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
            ZoneType.Exile, ZoneType.Command,
    };
    private static final String[] MANA_KEYS = {"W", "U", "B", "R", "G", "C"};

    private ObsSnapshot() {
    }

    static void write(StringBuilder sb, Game g) {
        // Registered players, not getPlayers(): the latter drops losers, which
        // would shrink and REINDEX seats mid-record. Seat order is frame-stable.
        List<Player> players = new ArrayList<>();
        for (Player p : g.getRegisteredPlayers()) {
            players.add(p);
        }

        // ---- glob ----
        PhaseHandler ph = g.getPhaseHandler();
        PhaseType pt = ph.getPhase();
        sb.append("{\"glob\":{\"turn\":").append(ph.getTurn())
                .append(",\"ph\":").append(Obs.q(pt == null ? null : pt.toString()))
                .append(",\"ap\":").append(players.indexOf(ph.getPlayerTurn()));
        if (g.getMonarch() != null) {
            sb.append(",\"mono\":").append(players.indexOf(g.getMonarch()));
        }
        if (g.getHasInitiative() != null) {
            sb.append(",\"init\":").append(players.indexOf(g.getHasInitiative()));
        }
        if (g.isDay()) {
            sb.append(",\"day\":\"day\"");
        } else if (g.isNight()) {
            sb.append(",\"day\":\"night\"");
        }
        sb.append('}');

        // ---- players ----
        sb.append(",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"life\":").append(p.getLife());
            if (p.hasLost()) {
                sb.append(",\"lost\":1");
            }
            counters(sb, p.getCounters());
            mana(sb, p);
            if (p.getLandsPlayedThisTurn() > 0) {
                sb.append(",\"lands\":").append(p.getLandsPlayedThisTurn());
            }
            sb.append(",\"hand\":").append(p.getCardsIn(ZoneType.Hand).size())
                    .append(",\"lib\":").append(p.getCardsIn(ZoneType.Library).size());
            List<Card> commanders = p.getCommanders();
            if (!commanders.isEmpty()) {
                sb.append(",\"cmdcast\":[");
                for (int j = 0; j < commanders.size(); j++) {
                    if (j > 0) {
                        sb.append(',');
                    }
                    sb.append(p.getCommanderCast(commanders.get(j)));
                }
                sb.append(']');
            }
            sb.append('}');
        }
        sb.append(']');

        // ---- combat annotations, applied during the entity walk ----
        Map<Integer, String> atk = new HashMap<>();
        Map<Integer, List<Integer>> blk = new HashMap<>();
        Combat combat = g.getCombat();
        if (combat != null) {
            for (Card attacker : combat.getAttackers()) {
                GameEntity def = combat.getDefenderByAttacker(attacker);
                if (def instanceof Player) {
                    atk.put(attacker.getId(), "{\"pi\":" + players.indexOf(def) + "}");
                } else if (def instanceof Card) {
                    atk.put(attacker.getId(), "{\"e\":" + ((Card) def).getId() + "}");
                }
                for (Card blocker : combat.getBlockers(attacker)) {
                    blk.computeIfAbsent(blocker.getId(), k -> new ArrayList<>()).add(attacker.getId());
                }
            }
        }

        // ---- entities: per-player zones + the shared stack zone, sorted (zone, id) ----
        sb.append(",\"ents\":[");
        boolean first = true;
        for (ZoneType z : ZONES) {
            List<Card> cards = new ArrayList<>();
            for (Player p : players) {
                for (Card c : p.getCardsIn(z)) {
                    cards.add(c);
                }
            }
            cards.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
            for (Card c : cards) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                entity(sb, c, z, players, atk, blk);
            }
        }
        List<Card> stackCards = new ArrayList<>();
        for (Card c : g.getCardsIn(ZoneType.Stack)) {
            stackCards.add(c);
        }
        stackCards.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        for (Card c : stackCards) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            entity(sb, c, ZoneType.Stack, players, atk, blk);
        }
        sb.append(']');

        // ---- stack (spell/ability instances, top-first) ----
        if (!g.getStack().isEmpty()) {
            sb.append(",\"stack\":[");
            int i = 0;
            for (SpellAbilityStackInstance si : g.getStack()) {
                if (i++ > 0) {
                    sb.append(',');
                }
                sb.append('{');
                SpellAbility sa = si.getSpellAbility();
                if (sa != null && sa.getHostCard() != null) {
                    sb.append("\"e\":").append(sa.getHostCard().getId()).append(',');
                }
                sb.append("\"c\":").append(players.indexOf(si.getActivatingPlayer()))
                        .append(",\"lbl\":").append(Obs.q(trunc(String.valueOf(sa == null ? si : sa))));
                if (si.getTargetChoices() != null && !si.getTargetChoices().isEmpty()) {
                    sb.append(",\"tgt\":[");
                    int j = 0;
                    for (Object tgt : si.getTargetChoices()) {
                        if (tgt instanceof Card) {
                            sb.append(j++ > 0 ? "," : "").append("{\"e\":").append(((Card) tgt).getId()).append('}');
                        } else if (tgt instanceof Player) {
                            sb.append(j++ > 0 ? "," : "").append("{\"pi\":").append(players.indexOf(tgt)).append('}');
                        }
                    }
                    sb.append(']');
                }
                sb.append('}');
            }
            sb.append(']');
        }
        sb.append('}');
    }

    private static void entity(StringBuilder sb, Card c, ZoneType z, List<Player> players,
            Map<Integer, String> atk, Map<Integer, List<Integer>> blk) {
        sb.append("{\"e\":").append(c.getId())
                .append(",\"n\":").append(Obs.q(identity(c)))
                .append(",\"z\":").append(Obs.q(z.toString().toLowerCase()));
        int ctrl = players.indexOf(c.getController());
        int own = players.indexOf(c.getOwner());
        sb.append(",\"c\":").append(ctrl);
        if (own != ctrl) {
            sb.append(",\"o\":").append(own);
        }
        if (c.isToken() || c.isTokenCard() || c.isEmblem()) {
            sb.append(",\"tok\":1");
        }
        if (c.isTapped()) {
            sb.append(",\"tap\":1");
        }
        if (z == ZoneType.Battlefield && c.isSick()) {
            sb.append(",\"sick\":1");
        }
        if (c.isPhasedOut()) {
            sb.append(",\"phz\":1");
        }
        if (c.isFaceDown()) {
            sb.append(",\"fd\":1");
        }
        if (c.getDamage() > 0) {
            sb.append(",\"dmg\":").append(c.getDamage());
        }
        if (z == ZoneType.Battlefield && c.isCreature()) {
            sb.append(",\"pt\":[").append(c.getNetPower()).append(',').append(c.getNetToughness()).append(']');
        }
        counters(sb, c.getCounters());
        Card attachedTo = c.getAttachedTo();
        if (attachedTo != null) {
            sb.append(",\"att\":").append(attachedTo.getId());
        } else {
            GameEntity ge = c.getEntityAttachedTo();
            if (ge instanceof Player) {
                sb.append(",\"attp\":").append(players.indexOf(ge));
            }
        }
        String atkRef = atk.get(c.getId());
        if (atkRef != null) {
            sb.append(",\"atk\":").append(atkRef);
        }
        List<Integer> blocking = blk.get(c.getId());
        if (blocking != null) {
            sb.append(",\"blk\":").append(blocking);
        }
        String vis = visDeviation(c, z, players);
        if (vis != null) {
            sb.append(",\"vis\":\"").append(vis).append('"');
        }
        sb.append('}');
    }

    /** Real identity even when face-down; "vis" gates who may know it. */
    private static String identity(Card c) {
        if (c.getPaperCard() != null) {
            return c.getPaperCard().getName();
        }
        return c.getName();
    }

    /**
     * Visibility only when deviating from the zone default (public everywhere
     * we walk, except hand = controller-only). Best-effort v1: revealed hands
     * and face-down knowledge via mayPlayerLook.
     */
    private static String visDeviation(Card c, ZoneType z, List<Player> players) {
        if (z == ZoneType.Hand) {
            for (Player p : players) {
                if (p != c.getController() && !c.mayPlayerLook(p)) {
                    return null; // someone can't see it: default controller-only
                }
            }
            return players.size() > 1 ? "all" : null; // revealed to everyone
        }
        if (c.isFaceDown()) {
            return c.mayPlayerLook(c.getController()) ? "c" : "none";
        }
        return null;
    }

    private static void counters(StringBuilder sb, Multiset<CounterType> counters) {
        if (counters == null || counters.isEmpty()) {
            return;
        }
        sb.append(",\"cnt\":{");
        int i = 0;
        for (Multiset.Entry<CounterType> e : counters.entrySet()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append(Obs.q(e.getElement().toString())).append(':').append(e.getCount());
        }
        sb.append('}');
    }

    private static void mana(StringBuilder sb, Player p) {
        boolean any = false;
        for (int i = 0; i < MagicColor.WUBRGC.length; i++) {
            int n = p.getManaPool().getAmountOfColor(MagicColor.WUBRGC[i]);
            if (n > 0) {
                sb.append(any ? "," : ",\"mana\":{").append('"').append(MANA_KEYS[i]).append("\":").append(n);
                any = true;
            }
        }
        if (any) {
            sb.append('}');
        }
    }

    private static String trunc(String s) {
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
