package forge.ai.anvil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.ai.ComputerUtilCombat;
import forge.card.ICardFace;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

/**
 * M12 Build 3 enumerators (m12-plan.md canonical shape §6; ADR-0103): the
 * decision SURFACES of design-doc §3d′ as search tags. Every non-priority
 * callback the heuristic still answers gets, in one hand-owned class (the
 * generated wrapper stays logic-free):
 *
 *   dec    the observation record names the option list (entity ids / SA
 *          labels / names, raw JSON in "opts") and adds option cards that
 *          live in unwalked zones (library tutor targets) to the snapshot —
 *          before this the record carried only a count;
 *   force  on a search copy armed with a {@link SurfaceDirective} for this
 *          (seat, kind, ordinal), the directive's index answer replaces the
 *          heuristic's — the natural line is always answer 0 of every
 *          enumeration, so a surface can never be worse than its fallback;
 *   after  on a search copy (a {@link SearchDirective} armed for the seat)
 *          the callback is TRACED — kind, ordinal, option count, min/max and
 *          the heuristic's answer as indices — so the search monitor can
 *          expand it on its next round (the anytime shape: one ply over the
 *          priority options first, then the surfaces on the path);
 *   enumerate  index-based candidate answers per ANSWER SHAPE (entity set,
 *          ordering, name ranking — the three of §6, plus the scry partition
 *          and the combat-damage vector), natural first, deduplicated,
 *          capped, seeded (CRN across copies).
 *
 * Inert on every mainline except the recording: no directive, no trace, the
 * heuristic answers exactly as before (ADR-0025 identity direction). All
 * failures are null returns / counters, never exceptions into the game thread.
 */
public final class Surfaces {

    public static final int ENTITY_ONE = 0;
    public static final int ENTITY_SET = 1;
    public static final int ORDER = 2;
    public static final int SCRY = 3;
    public static final int MODE = 4;
    public static final int NAME = 5;
    public static final int DAMAGE = 6;
    public static final String[] KIND_NAMES = {
        "entity_one", "entity_set", "order", "scry", "mode", "name", "damage"};

    /** Enumeration cap per callback (the search's -searchopts analogue). */
    public static final int DEFAULT_CAP = 12;

    private Surfaces() {
    }

    // ------------------------------------------------------------------
    // Option rendering (raw JSON entries for Obs "opts")

    static String optJson(Object o) {
        if (o instanceof Card) {
            return "{\"e\":" + ((Card) o).getId() + "}";
        }
        if (o instanceof Player) {
            Player p = (Player) o;
            return "{\"pi\":" + p.getGame().getRegisteredPlayers().indexOf(p) + "}";
        }
        if (o instanceof SpellAbility) {
            SpellAbility sa = (SpellAbility) o;
            Card h = sa.getHostCard();
            return "{\"e\":" + (h == null ? -1 : h.getId()) + ",\"sa\":" + Obs.q(Census.str(sa)) + "}";
        }
        if (o instanceof ICardFace) {
            return Obs.q(((ICardFace) o).getName());
        }
        return Obs.q(o == null ? "null" : String.valueOf(o));
    }

    static List<String> optList(Iterable<?> options) {
        List<String> out = new ArrayList<>();
        if (options == null) {
            return out;
        }
        for (Object o : options) {
            out.add(optJson(o));
        }
        return out;
    }

    /** Option cards outside the walked zones (library, or zoneless) — the
     *  snapshot must contain them or the option references nothing. */
    static List<Card> extras(Iterable<?> options) {
        List<Card> extra = null;
        if (options == null) {
            return null;
        }
        for (Object o : options) {
            Card c = o instanceof Card ? (Card) o
                    : o instanceof SpellAbility ? ((SpellAbility) o).getHostCard() : null;
            if (c == null) {
                continue;
            }
            boolean hidden;
            try {
                hidden = c.getZone() == null || c.getZone().getZoneType() == ZoneType.Library;
            } catch (Exception e) {
                hidden = false;
            }
            if (hidden) {
                if (extra == null) {
                    extra = new ArrayList<>(4);
                }
                if (!extra.contains(c)) {
                    extra.add(c);
                }
            }
        }
        return extra;
    }

    /** The named-options observation record. kv = the generated wrapper's
     *  scalar summary (unchanged); the kind rides as "surf". */
    public static long dec(Game g, Player p, String m, int kind, Iterable<?> options, Object... kv) {
        try {
            Object[] kv2 = Arrays.copyOf(kv, kv.length + 2);
            kv2[kv.length] = "surf";
            kv2[kv.length + 1] = KIND_NAMES[kind];
            return Obs.decSurface(g, p, m, optList(options), extras(options), kv2);
        } catch (Exception e) {
            return Obs.dec(g, p, m, kv);
        }
    }

    // ------------------------------------------------------------------
    // Index helpers

    static <T> List<T> asList(Iterable<T> it) {
        List<T> out = new ArrayList<>();
        if (it != null) {
            for (T t : it) {
                out.add(t);
            }
        }
        return out;
    }

    static int[] indicesOf(List<?> options, Iterable<?> chosen) {
        List<Integer> out = new ArrayList<>();
        if (chosen != null) {
            for (Object c : chosen) {
                int i = identityIndex(options, c);
                if (i >= 0) {
                    out.add(i);
                }
            }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }

    static int identityIndex(List<?> options, Object o) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i) == o) {
                return i;
            }
        }
        // name-valued answers (chooseCardName / chooseSomeType)
        if (o instanceof String) {
            for (int i = 0; i < options.size(); i++) {
                Object x = options.get(i);
                String n = x instanceof ICardFace ? ((ICardFace) x).getName() : String.valueOf(x);
                if (o.equals(n)) {
                    return i;
                }
            }
        }
        return -1;
    }

    static boolean validIndices(int[] a, int n, int min, int max, boolean distinct) {
        if (a == null || a.length < min || a.length > max) {
            return false;
        }
        Set<Integer> seen = new HashSet<>();
        for (int i : a) {
            if (i < 0 || i >= n) {
                return false;
            }
            if (distinct && !seen.add(i)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Enumerators: index answers, natural first, deduplicated, capped.

    private static String key(int[] a) {
        return Arrays.toString(a);
    }

    private static void add(List<int[]> out, Set<String> seen, int[] a, int cap) {
        if (out.size() >= cap) {
            return;
        }
        if (seen.add(key(a))) {
            out.add(a);
        }
    }

    /**
     * @param kind    the answer shape
     * @param n       option count
     * @param min     minimum answer size (ENTITY_SET / MODE); ignored otherwise
     * @param max     maximum answer size (ENTITY_SET / MODE); DAMAGE: the damage
     *                to deal; ignored otherwise
     * @param natural the heuristic's answer (always index 0 of the result)
     * @param aux     DAMAGE: lethal damage per blocker (length n) and, as the
     *                last element, 1 if the remainder may go to the defender
     *                (trample); null otherwise
     */
    public static List<int[]> enumerate(int kind, int n, int min, int max, int[] natural,
            int[] aux, int cap, Random rng) {
        List<int[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        cap = Math.max(1, cap);
        if (natural != null) {
            add(out, seen, natural.clone(), cap);
        }
        switch (kind) {
            case ENTITY_ONE:
            case NAME:
                if (n <= cap) {
                    for (int i = 0; i < n; i++) {
                        add(out, seen, new int[] {i}, cap);
                    }
                } else {
                    for (int i : sample(n, cap - 1, rng)) {
                        add(out, seen, new int[] {i}, cap);
                    }
                }
                break;
            case ENTITY_SET:
            case MODE: {
                int k = natural == null ? Math.max(min, 0) : natural.length;
                k = Math.max(min, Math.min(k, max));
                long total = choose(n, k);
                if (total > 0 && total <= cap) {
                    for (int[] c : combinations(n, k)) {
                        add(out, seen, c, cap);
                    }
                } else if (natural != null) {
                    // single swaps around the natural set, then seeded k-subsets
                    List<Integer> in = new ArrayList<>();
                    List<Integer> outside = new ArrayList<>();
                    Set<Integer> ns = new HashSet<>();
                    for (int i : natural) {
                        ns.add(i);
                        in.add(i);
                    }
                    for (int i = 0; i < n; i++) {
                        if (!ns.contains(i)) {
                            outside.add(i);
                        }
                    }
                    List<int[]> swaps = new ArrayList<>();
                    for (int a : in) {
                        for (int b : outside) {
                            int[] s = natural.clone();
                            for (int j = 0; j < s.length; j++) {
                                if (s[j] == a) {
                                    s[j] = b;
                                }
                            }
                            Arrays.sort(s);
                            swaps.add(s);
                        }
                    }
                    shuffle(swaps, rng);
                    for (int[] s : swaps) {
                        add(out, seen, s, cap);
                    }
                    while (out.size() < cap) {
                        int[] s = sample(n, k, rng);
                        Arrays.sort(s);
                        if (!seen.add(key(s))) {
                            break;
                        }
                        out.add(s);
                    }
                }
                // size-neighbours inside [min, max]: drop one / add one
                if (natural != null && out.size() < cap) {
                    if (natural.length > min && natural.length > 0) {
                        int[] s = Arrays.copyOf(natural, natural.length - 1);
                        add(out, seen, s, cap);
                    }
                    if (natural.length < max && natural.length < n) {
                        Set<Integer> ns = new HashSet<>();
                        for (int i : natural) {
                            ns.add(i);
                        }
                        for (int i = 0; i < n && out.size() < cap; i++) {
                            if (!ns.contains(i)) {
                                int[] s = Arrays.copyOf(natural, natural.length + 1);
                                s[natural.length] = i;
                                Arrays.sort(s);
                                add(out, seen, s, cap);
                                break;
                            }
                        }
                    }
                }
                break;
            }
            case ORDER: {
                long total = factorial(n);
                if (total > 0 && total <= cap) {
                    for (int[] perm : permutations(n)) {
                        add(out, seen, perm, cap);
                    }
                } else {
                    int[] base = natural != null && natural.length == n ? natural : identity(n);
                    List<int[]> swaps = new ArrayList<>();
                    for (int i = 0; i + 1 < n; i++) {
                        int[] s = base.clone();
                        int t = s[i];
                        s[i] = s[i + 1];
                        s[i + 1] = t;
                        swaps.add(s);
                    }
                    shuffle(swaps, rng);
                    for (int[] s : swaps) {
                        add(out, seen, s, cap);
                    }
                    while (out.size() < cap) {
                        int[] s = identity(n);
                        shuffleInts(s, rng);
                        if (!seen.add(key(s))) {
                            break;
                        }
                        out.add(s);
                    }
                }
                break;
            }
            case SCRY: {
                // one bit per card: 0 = top (input order kept), 1 = bottom / graveyard
                if (n <= 0) {
                    break;
                }
                long total = 1L << Math.min(n, 20);
                if (total <= cap) {
                    for (int mask = 0; mask < total; mask++) {
                        int[] a = new int[n];
                        for (int i = 0; i < n; i++) {
                            a[i] = (mask >> i) & 1;
                        }
                        add(out, seen, a, cap);
                    }
                } else {
                    int[] base = natural != null && natural.length == n ? natural : new int[n];
                    for (int i = 0; i < n && out.size() < cap; i++) {
                        int[] a = base.clone();
                        a[i] ^= 1;
                        add(out, seen, a, cap);
                    }
                    add(out, seen, new int[n], cap);
                    int[] all = new int[n];
                    Arrays.fill(all, 1);
                    add(out, seen, all, cap);
                }
                break;
            }
            case DAMAGE: {
                // aux = lethal per blocker (n) + [trample flag]; answer = damage per
                // blocker + the defender's share as the last slot. Legal shapes
                // under the ordered rule: lethal in order, remainder on one
                // blocker k (or the defender with trample).
                if (aux == null || aux.length != n + 1 || max <= 0) {
                    break;
                }
                boolean trample = aux[n] != 0;
                for (int k = 0; k <= n && out.size() < cap; k++) {
                    if (k == n && !trample) {
                        break;
                    }
                    int[] a = new int[n + 1];
                    int left = max;
                    for (int i = 0; i < n && left > 0; i++) {
                        int d = Math.min(left, Math.max(0, aux[i]));
                        if (i == k) {
                            break; // the rest lands here
                        }
                        a[i] = d;
                        left -= d;
                    }
                    a[k] += left;
                    add(out, seen, a, cap);
                }
                break;
            }
            default:
                break;
        }
        return out;
    }

    static int[] identity(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    static int[] sample(int n, int k, Random rng) {
        int[] a = identity(n);
        shuffleInts(a, rng);
        return Arrays.copyOf(a, Math.max(0, Math.min(k, n)));
    }

    static void shuffleInts(int[] a, Random rng) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    static <T> void shuffle(List<T> l, Random rng) {
        for (int i = l.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            T t = l.get(i);
            l.set(i, l.get(j));
            l.set(j, t);
        }
    }

    static long choose(int n, int k) {
        if (k < 0 || k > n) {
            return 0;
        }
        long r = 1;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;
            if (r > 1_000_000) {
                return 1_000_001;
            }
        }
        return r;
    }

    static long factorial(int n) {
        long r = 1;
        for (int i = 2; i <= n; i++) {
            r *= i;
            if (r > 1_000_000) {
                return 1_000_001;
            }
        }
        return r;
    }

    static List<int[]> combinations(int n, int k) {
        List<int[]> out = new ArrayList<>();
        int[] c = new int[k];
        combine(out, c, 0, 0, n, k);
        return out;
    }

    private static void combine(List<int[]> out, int[] c, int pos, int start, int n, int k) {
        if (pos == k) {
            out.add(c.clone());
            return;
        }
        for (int i = start; i < n; i++) {
            c[pos] = i;
            combine(out, c, pos + 1, i + 1, n, k);
        }
    }

    static List<int[]> permutations(int n) {
        List<int[]> out = new ArrayList<>();
        permute(out, identity(n), 0);
        return out;
    }

    private static void permute(List<int[]> out, int[] a, int pos) {
        if (pos == a.length) {
            out.add(a.clone());
            return;
        }
        for (int i = pos; i < a.length; i++) {
            int t = a[pos];
            a[pos] = a[i];
            a[i] = t;
            permute(out, a, pos + 1);
            a[i] = a[pos];
            a[pos] = t;
        }
    }

    // ------------------------------------------------------------------
    // Trace (search copies): the heuristic's answer as indices

    static void trace(Game g, Player p, int kind, String label, int n, int min, int max,
            int[] natural, int[] aux) {
        try {
            // Trivial surfaces (one option, or nothing to pick) are not
            // decisions: never traced, so the expansion round never spends
            // copies on them (the first smoke expanded 1-element orderings).
            if (!nontrivial(kind, n, max)) {
                return;
            }
            SearchDirective sr = SearchDirective.active(g, p);
            if (sr == null || !sr.applied) {
                return;
            }
            sr.noteSurface(kind, label, n, min, max, natural, aux);
        } catch (Exception ignored) {
        }
    }

    /** A surface callback is a decision only with ≥ 2 options and something
     *  to pick (damage: the amount is max). Shared by the trace and the
     *  directive so ordinals agree. */
    public static boolean nontrivial(int kind, int n, int max) {
        return n >= 2 && (max >= 1 || kind == DAMAGE);
    }

    static String labelOf(SpellAbility sa) {
        String s = Census.str(sa);
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // Hooks per callback (called from the generated wrappers)

    // ---- ENTITY_ONE

    public static <T extends GameEntity> T forceEntityOne(Game g, Player p, FCollectionView<T> optionList,
            SpellAbility sa) {
        try {
            List<T> opts = asList(optionList);
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_ONE, opts.size(), 1);
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, opts.size(), 1, 1, true)) {
                d.miss("idx");
                return null;
            }
            d.fired(opts.size());
            return opts.get(d.answer[0]);
        } catch (Exception e) {
            return null;
        }
    }

    public static void afterEntityOne(Game g, Player p, Iterable<?> optionList, SpellAbility sa, Object chosen) {
        List<?> opts = asList(optionList);
        int i = chosen == null ? -1 : identityIndex(opts, chosen);
        trace(g, p, ENTITY_ONE, labelOf(sa), opts.size(), 1, 1, i < 0 ? null : new int[] {i}, null);
    }

    public static SpellAbility forceSpellOne(Game g, Player p, List<SpellAbility> spells, SpellAbility sa) {
        try {
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_ONE, spells.size(), 1);
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, spells.size(), 1, 1, true)) {
                d.miss("idx");
                return null;
            }
            d.fired(spells.size());
            return spells.get(d.answer[0]);
        } catch (Exception e) {
            return null;
        }
    }

    public static Card forceZoneChange(Game g, Player p, CardCollection fetchList, SpellAbility sa) {
        try {
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_ONE, fetchList.size(), 1);
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, fetchList.size(), 1, 1, true)) {
                d.miss("idx");
                return null;
            }
            d.fired(fetchList.size());
            return fetchList.get(d.answer[0]);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- ENTITY_SET

    public static <T extends GameEntity> List<T> forceEntitySet(Game g, Player p, FCollectionView<T> optionList,
            int min, int max, SpellAbility sa) {
        try {
            List<T> opts = asList(optionList);
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_SET, opts.size(), max);
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, opts.size(), min, max, true)) {
                d.miss("idx");
                return null;
            }
            d.fired(opts.size());
            List<T> out = new ArrayList<>(d.answer.length);
            for (int i : d.answer) {
                out.add(opts.get(i));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static CardCollection forceCardSet(Game g, Player p, Iterable<Card> options, int min, int max,
            SpellAbility sa) {
        try {
            List<Card> opts = asList(options);
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_SET, opts.size(), max);
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, opts.size(), min, max, true)) {
                d.miss("idx");
                return null;
            }
            d.fired(opts.size());
            CardCollection out = new CardCollection();
            for (int i : d.answer) {
                out.add(opts.get(i));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<SpellAbility> forceSpellSet(Game g, Player p, List<SpellAbility> spells, int num,
            SpellAbility sa) {
        try {
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_SET, spells.size(), Math.max(0, num));
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, spells.size(), 0, Math.max(0, num), true)) {
                d.miss("idx");
                return null;
            }
            d.fired(spells.size());
            List<SpellAbility> out = new ArrayList<>();
            for (int i : d.answer) {
                out.add(spells.get(i));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static void afterEntitySet(Game g, Player p, Iterable<?> optionList, int min, int max, SpellAbility sa,
            Iterable<?> chosen) {
        List<?> opts = asList(optionList);
        trace(g, p, ENTITY_SET, labelOf(sa), opts.size(), min, max, indicesOf(opts, chosen), null);
    }

    // ---- ORDER

    public static List<SpellAbility> forceOrderSa(Game g, Player p, List<SpellAbility> sas) {
        try {
            SurfaceDirective d = SurfaceDirective.match(g, p, ORDER, sas.size(), sas.size());
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, sas.size(), sas.size(), sas.size(), true)) {
                d.miss("idx");
                return null;
            }
            d.fired(sas.size());
            List<SpellAbility> out = new ArrayList<>(sas.size());
            for (int i : d.answer) {
                out.add(sas.get(i));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static CardCollection forceOrderCards(Game g, Player p, Iterable<Card> cards) {
        try {
            List<Card> opts = asList(cards);
            SurfaceDirective d = SurfaceDirective.match(g, p, ORDER, opts.size(), opts.size());
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, opts.size(), opts.size(), opts.size(), true)) {
                d.miss("idx");
                return null;
            }
            d.fired(opts.size());
            CardCollection out = new CardCollection();
            for (int i : d.answer) {
                out.add(opts.get(i));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static void afterOrder(Game g, Player p, Iterable<?> input, SpellAbility sa, Iterable<?> ordered) {
        List<?> opts = asList(input);
        trace(g, p, ORDER, labelOf(sa), opts.size(), opts.size(), opts.size(), indicesOf(opts, ordered), null);
    }

    // ---- SCRY (arrangeForScry / arrangeForSurveil): pair = (top, bottom|graveyard)

    public static ImmutablePair<CardCollection, CardCollection> forceScry(Game g, Player p, CardCollection topN) {
        try {
            int n = topN.size();
            SurfaceDirective d = SurfaceDirective.match(g, p, SCRY, n, n);
            if (d == null) {
                return null;
            }
            if (d.answer == null || d.answer.length != n) {
                d.miss("idx");
                return null;
            }
            d.fired(n);
            CardCollection top = new CardCollection();
            CardCollection away = new CardCollection();
            for (int i = 0; i < n; i++) {
                (d.answer[i] == 0 ? top : away).add(topN.get(i));
            }
            return ImmutablePair.of(top, away);
        } catch (Exception e) {
            return null;
        }
    }

    public static void afterScry(Game g, Player p, CardCollection topN,
            ImmutablePair<CardCollection, CardCollection> r) {
        int n = topN == null ? 0 : topN.size();
        int[] nat = new int[n];
        if (r != null && r.getRight() != null) {
            for (int i = 0; i < n; i++) {
                nat[i] = r.getRight().contains(topN.get(i)) ? 1 : 0;
            }
        }
        trace(g, p, SCRY, "", n, n, n, nat, null);
    }

    // ---- MODE

    public static List<AbilitySub> forceMode(Game g, Player p, SpellAbility sa, List<AbilitySub> possible,
            int min, int num, boolean allowRepeat) {
        try {
            SurfaceDirective d = SurfaceDirective.match(g, p, MODE, possible.size(), num);
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, possible.size(), min, num, !allowRepeat)) {
                d.miss("idx");
                return null;
            }
            d.fired(possible.size());
            List<AbilitySub> out = new ArrayList<>(d.answer.length);
            for (int i : d.answer) {
                out.add(possible.get(i));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static void afterMode(Game g, Player p, SpellAbility sa, List<AbilitySub> possible, int min, int num,
            List<AbilitySub> chosen) {
        trace(g, p, MODE, labelOf(sa), possible == null ? 0 : possible.size(), min, num,
                indicesOf(asList(possible), chosen), null);
    }

    // ---- NAME (chooseCardName over faces; chooseSomeType over validTypes)

    public static String forceName(Game g, Player p, Iterable<?> names, SpellAbility sa) {
        try {
            List<?> opts = asList(names);
            SurfaceDirective d = SurfaceDirective.match(g, p, NAME, opts.size(), 1);
            if (d == null) {
                return null;
            }
            if (!validIndices(d.answer, opts.size(), 1, 1, true)) {
                d.miss("idx");
                return null;
            }
            d.fired(opts.size());
            Object o = opts.get(d.answer[0]);
            return o instanceof ICardFace ? ((ICardFace) o).getName() : String.valueOf(o);
        } catch (Exception e) {
            return null;
        }
    }

    public static void afterName(Game g, Player p, Iterable<?> names, SpellAbility sa, String chosen) {
        List<?> opts = asList(names);
        int i = identityIndex(opts, chosen);
        trace(g, p, NAME, labelOf(sa), opts.size(), 1, 1, i < 0 ? null : new int[] {i}, null);
    }

    // ---- DAMAGE (assignCombatDamage): answer = per-blocker damage + defender share

    static int[] lethalAux(Player p, Card attacker, CardCollectionView blockers, int damage, GameEntity defender,
            boolean overrideOrder) {
        int n = blockers.size();
        int[] aux = new int[n + 1];
        for (int i = 0; i < n; i++) {
            try {
                aux[i] = Math.max(0, ComputerUtilCombat.getEnoughDamageToKill(blockers.get(i), damage, attacker, true));
            } catch (Exception e) {
                aux[i] = damage;
            }
        }
        boolean trample = false;
        try {
            trample = defender != null && attacker.hasKeyword(forge.game.keyword.Keyword.TRAMPLE);
        } catch (Exception ignored) {
        }
        aux[n] = trample ? 1 : 0;
        return aux;
    }

    public static Map<Card, Integer> forceDamage(Game g, Player p, Card attacker, CardCollectionView blockers,
            int damageDealt, GameEntity defender, boolean overrideOrder) {
        try {
            int n = blockers.size();
            SurfaceDirective d = SurfaceDirective.match(g, p, DAMAGE, n, damageDealt);
            if (d == null) {
                return null;
            }
            if (d.answer == null || d.answer.length != n + 1) {
                d.miss("idx");
                return null;
            }
            int sum = 0;
            for (int v : d.answer) {
                if (v < 0) {
                    d.miss("neg");
                    return null;
                }
                sum += v;
            }
            if (sum != damageDealt) {
                d.miss("sum");
                return null;
            }
            d.fired(n);
            Map<Card, Integer> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                if (d.answer[i] > 0) {
                    out.put(blockers.get(i), d.answer[i]);
                }
            }
            if (d.answer[n] > 0 && defender instanceof Card) {
                out.put((Card) defender, d.answer[n]);
            } else if (d.answer[n] > 0) {
                out.put(null, d.answer[n]); // the defending player (distributeAIDamage's convention)
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static void afterDamage(Game g, Player p, Card attacker, CardCollectionView blockers, int damageDealt,
            GameEntity defender, boolean overrideOrder, Map<Card, Integer> r) {
        try {
            int n = blockers == null ? 0 : blockers.size();
            int[] nat = new int[n + 1];
            if (r != null) {
                for (Map.Entry<Card, Integer> e : r.entrySet()) {
                    int i = e.getKey() == null ? -1 : blockers.indexOf(e.getKey());
                    if (i >= 0) {
                        nat[i] += e.getValue();
                    } else {
                        nat[n] += e.getValue();
                    }
                }
            }
            trace(g, p, DAMAGE, attacker == null ? "" : attacker.getName(), n, damageDealt, damageDealt, nat,
                    lethalAux(p, attacker, blockers, damageDealt, defender, overrideOrder));
        } catch (Exception ignored) {
        }
    }

    /** Type names as a list (chooseSomeType hands a Collection). */
    public static List<String> names(Collection<String> validTypes) {
        return validTypes == null ? new ArrayList<>() : new ArrayList<>(validTypes);
    }
}
