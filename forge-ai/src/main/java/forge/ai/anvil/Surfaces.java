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
    /** Evening 4 (ADR-0105): the payment window as a surface on search copies —
     *  options = {auto} ∪ the M9 goal options (PlayerControllerAnvil.copyPay),
     *  one pick; served by the pay head over its own tag, never through here. */
    public static final int PAY = 7;
    public static final String[] KIND_NAMES = {
        "entity_one", "entity_set", "order", "scry", "mode", "name", "damage", "pay"};

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
            String k = AbilityKey.note(sa); // ADR-0105: the ability's identity beside the render
            return "{\"e\":" + (h == null ? -1 : h.getId()) + ",\"sa\":" + Obs.q(Census.str(sa))
                    + (k == null ? "" : ",\"ak\":\"" + k + "\"") + "}";
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
            // ADR-0105: a SpellAbility value ("sak", the resolving ability) becomes
            // its ability key (its canonical text registered with the session)
            for (int i = 1; i < kv2.length; i += 2) {
                if (kv2[i] instanceof SpellAbility) {
                    String k = AbilityKey.note((SpellAbility) kv2[i]);
                    kv2[i] = k == null ? "" : k;
                }
            }
            String by = bridgeFor(p, kind) != null ? "bridge" : null;
            long s = Obs.decSurface(g, p, m, by, optList(options), extras(options), kv2);
            // evening 2: the frame of the surface window a SurfaceDirective is
            // about to answer (the copy's wire-session dec record, already
            // built for the bridge) — the sub row's state for distillation
            SurfaceDirective sd = SurfaceDirective.pending(g, p, kind);
            if (sd != null) {
                sd.pendingFrame = Obs.lastDecForBridge(g);
            }
            return s;
        } catch (Exception e) {
            return Obs.dec(g, p, m, kv);
        }
    }

    // ------------------------------------------------------------------
    // ADR-0105: the bridged answer (the model serving a surface)

    static String tagOf(int kind) {
        switch (kind) {
            case ENTITY_ONE: return PlayerControllerAnvil.TAG_SURFACE_ONE;
            case ENTITY_SET: return PlayerControllerAnvil.TAG_SURFACE_SET;
            case MODE: return PlayerControllerAnvil.TAG_SURFACE_MODE;
            case ORDER: return PlayerControllerAnvil.TAG_SURFACE_ORDER;
            case DAMAGE: return PlayerControllerAnvil.TAG_SURFACE_DAMAGE;
            default: return null;
        }
    }

    /** The seat's bridge when it bridges this kind's tag; null = the natural line. */
    static AnvilBridge bridgeFor(Player p, int kind) {
        try {
            String tag = tagOf(kind);
            if (tag == null || p == null) {
                return null;
            }
            forge.game.player.PlayerController pc = p.getController();
            return pc instanceof PlayerControllerAnvil ? ((PlayerControllerAnvil) pc).bridgeFor(tag) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> labels(List<?> opts) {
        List<String> out = new ArrayList<>(opts.size());
        for (Object o : opts) {
            out.add(o instanceof SpellAbility ? labelOf((SpellAbility) o) : Census.str(o) == null ? "" : Census.str(o));
        }
        return out;
    }

    /** ENTITY_ONE over the bridge: the option index, or -1 (not bridged, a
     *  trivial window, an out-of-range answer — the natural line stands). */
    static int askOne(Game g, Player p, List<?> opts, String m) {
        AnvilBridge b = bridgeFor(p, ENTITY_ONE);
        if (b == null || !nontrivial(ENTITY_ONE, opts.size(), 1)) {
            return -1;
        }
        int i = b.selectOne(PlayerControllerAnvil.TAG_SURFACE_ONE, labels(opts));
        boolean ok = i >= 0 && i < opts.size();
        Census.rec(g, p, m, "by", "bridge", "n", opts.size(), "i", i, "ok", ok);
        if (ok) {
            // the served answer IS the natural line: trace it on search copies so the
            // expansion round still enumerates this surface (a bridged hook returns
            // before the wrapper's after-hook — found on the first served smoke, 09-07)
            trace(g, p, ENTITY_ONE, m, opts.size(), 1, 1, new int[] {i}, null);
        }
        return ok ? i : -1;
    }

    /** ENTITY_SET over the bridge: distinct indices within [min, max], or null. */
    static int[] askSet(Game g, Player p, List<?> opts, int min, int max, String m) {
        AnvilBridge b = bridgeFor(p, ENTITY_SET);
        if (b == null || !nontrivial(ENTITY_SET, opts.size(), max) || (min >= opts.size() && max >= opts.size())) {
            return null;
        }
        int[] a = b.selectSet(PlayerControllerAnvil.TAG_SURFACE_SET, labels(opts), min, max);
        boolean ok = a != null && validIndices(a, opts.size(), min, max, true);
        Census.rec(g, p, m, "by", "bridge", "n", opts.size(), "k", a == null ? -1 : a.length, "ok", ok);
        if (ok) {
            trace(g, p, ENTITY_SET, m, opts.size(), min, max, a, null); // the served answer = the natural line
        }
        return ok ? a : null;
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
        return enumerate(kind, n, min, max, natural, aux, cap, rng, false);
    }

    /** @param repeat ENTITY_SET / MODE: an option may be picked more than
     *                once (answers are sorted multisets); ignored otherwise */
    public static List<int[]> enumerate(int kind, int n, int min, int max, int[] natural,
            int[] aux, int cap, Random rng, boolean repeat) {
        List<int[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        cap = Math.max(1, cap);
        if (natural != null) {
            add(out, seen, natural.clone(), cap);
        }
        switch (kind) {
            case ENTITY_ONE:
            case NAME:
            case PAY:
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
            case MODE:
                enumerateSet(n, min, max, natural, cap, rng, repeat, out, seen);
                break;
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
                // blocker + the defender's share as the last slot. Under the rule
                // in force (no damage assignment order: GameRules.orderCombatants
                // off) any split is legal; the family enumerated is the kill
                // orders (damageFromSequence): every prefix of every blocker
                // permutation, each closed by the defender under trample — the
                // natural first, permutations in a seeded order over the cap.
                if (aux == null || aux.length != n + 1 || max <= 0) {
                    break;
                }
                boolean trample = aux[n] != 0;
                List<int[]> perms;
                if (factorial(n) <= 24) {
                    perms = permutations(n);
                    shuffle(perms, rng);
                } else {
                    perms = new ArrayList<>();
                    for (int t = 0; t < cap; t++) {
                        int[] s = identity(n);
                        shuffleInts(s, rng);
                        perms.add(s);
                    }
                }
                for (int[] perm : perms) {
                    if (out.size() >= cap) {
                        break;
                    }
                    for (int len = 1; len <= n && out.size() < cap; len++) {
                        int[] seq = Arrays.copyOf(perm, len);
                        int[] a = damageFromSequence(seq, n, max, aux);
                        if (a != null) {
                            add(out, seen, a, cap);
                        }
                        if (trample && out.size() < cap) {
                            int[] seq2 = Arrays.copyOf(perm, len + 1);
                            seq2[len] = n;
                            int[] a2 = damageFromSequence(seq2, n, max, aux);
                            if (a2 != null) {
                                add(out, seen, a2, cap);
                            }
                        }
                    }
                }
                if (trample && out.size() < cap) {
                    add(out, seen, damageFromSequence(new int[] {n}, n, max, aux), cap);
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

    /** ENTITY_SET / MODE (evening 2 fix): the natural's size k clamped into
     *  [min, max]; every k-subset (k-multiset under repeat) when they fit the
     *  cap, else single swaps around the natural then seeded draws; then the
     *  natural's size neighbours. Every non-natural answer sits inside
     *  [min, max] — the label run's mode misses were a size-1 neighbour under
     *  "choose two" and distinct subsets under "choose three, repeats
     *  allowed" (2,519 of 12,881 mode answers rejected at apply). */
    private static void enumerateSet(int n, int min, int max, int[] natural, int cap, Random rng,
            boolean repeat, List<int[]> out, Set<String> seen) {
        int k = natural == null ? Math.max(min, 0) : natural.length;
        k = Math.max(min, Math.min(k, max));
        if (!repeat) {
            k = Math.min(k, n);
        }
        if (k < min) {
            return; // unanswerable without repeats (min > n): the natural alone
        }
        long total = repeat ? choose(n + k - 1, k) : choose(n, k);
        if (total > 0 && total <= cap) {
            for (int[] c : repeat ? multisets(n, k) : combinations(n, k)) {
                add(out, seen, c, cap);
            }
        } else {
            if (natural != null && natural.length == k) {
                // single swaps around the natural (one position at a time)
                List<int[]> swaps = new ArrayList<>();
                for (int j = 0; j < natural.length; j++) {
                    for (int b = 0; b < n; b++) {
                        if (b == natural[j] || (!repeat && contains(natural, b))) {
                            continue;
                        }
                        int[] s = natural.clone();
                        s[j] = b;
                        Arrays.sort(s);
                        swaps.add(s);
                    }
                }
                shuffle(swaps, rng);
                for (int[] s : swaps) {
                    add(out, seen, s, cap);
                }
            }
            // seeded draws fill the cap (bounded tries: the space may be smaller than the cap)
            for (int tries = 0; out.size() < cap && tries < 4 * cap; tries++) {
                int[] s = repeat ? draw(n, k, rng) : sample(n, k, rng);
                Arrays.sort(s);
                add(out, seen, s, cap);
            }
        }
        // size-neighbours of the natural inside [min, max]: drop one / add one
        if (natural != null && out.size() < cap) {
            int drop = natural.length - 1;
            if (natural.length > 0 && drop >= min && drop <= max) {
                add(out, seen, Arrays.copyOf(natural, drop), cap);
            }
            int grow = natural.length + 1;
            if (grow >= min && grow <= max) {
                for (int i = 0; i < n && out.size() < cap; i++) {
                    if (repeat || !contains(natural, i)) {
                        int[] s = Arrays.copyOf(natural, grow);
                        s[natural.length] = i;
                        Arrays.sort(s);
                        add(out, seen, s, cap);
                        break;
                    }
                }
            }
        }
    }

    static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    /** k independent draws from [0, n) (a multiset sample; the caller sorts). */
    static int[] draw(int n, int k, Random rng) {
        int[] a = new int[Math.max(0, k)];
        for (int i = 0; i < a.length; i++) {
            a[i] = rng.nextInt(n);
        }
        return a;
    }

    /** Every k-multiset of [0, n) as a nondecreasing index sequence
     *  (C(n + k - 1, k) of them). */
    static List<int[]> multisets(int n, int k) {
        List<int[]> out = new ArrayList<>();
        multi(out, new int[Math.max(0, k)], 0, 0, n);
        return out;
    }

    private static void multi(List<int[]> out, int[] c, int pos, int start, int n) {
        if (pos == c.length) {
            out.add(c.clone());
            return;
        }
        for (int i = start; i < n; i++) {
            c[pos] = i;
            multi(out, c, pos + 1, i, n);
        }
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
        trace(g, p, kind, label, n, min, max, natural, aux, false);
    }

    /** repeat (evening 2): the callback allows an option more than once
     *  (chooseModeForAbility allowRepeat) — the enumerator emits multisets. */
    static void trace(Game g, Player p, int kind, String label, int n, int min, int max,
            int[] natural, int[] aux, boolean repeat) {
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
            sr.noteSurface(kind, label, n, min, max, natural, aux, repeat);
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
            if (Census.loopTripped(g)) {
                return opts.isEmpty() ? null : opts.get(0); // loop tripwire: let the engine's re-ask exit
            }
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_ONE, opts.size(), 1);
            if (d == null) {
                int i = askOne(g, p, opts, "chooseSingleEntityForEffect");
                return i < 0 ? null : opts.get(i);
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
            if (Census.loopTripped(g)) {
                return spells.isEmpty() ? null : spells.get(0);
            }
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_ONE, spells.size(), 1);
            if (d == null) {
                int i = askOne(g, p, spells, "chooseSingleSpellForEffect");
                return i < 0 ? null : spells.get(i);
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
            if (Census.loopTripped(g)) {
                return fetchList.isEmpty() ? null : fetchList.get(0);
            }
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_ONE, fetchList.size(), 1);
            if (d == null) {
                int i = askOne(g, p, fetchList, "chooseSingleCardForZoneChange");
                return i < 0 ? null : fetchList.get(i);
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
            if (Census.loopTripped(g)) {
                return new java.util.ArrayList<>(opts.subList(0, Math.min(opts.size(), Math.max(0, min))));
            }
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_SET, opts.size(), max);
            if (d == null) {
                int[] a = askSet(g, p, opts, min, max, "chooseEntitiesForEffect");
                if (a == null) {
                    return null;
                }
                List<T> out = new ArrayList<>(a.length);
                for (int i : a) {
                    out.add(opts.get(i));
                }
                return out;
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
            if (Census.loopTripped(g)) {
                return new CardCollection(opts.subList(0, Math.min(opts.size(), Math.max(0, min))));
            }
            SurfaceDirective d = SurfaceDirective.match(g, p, ENTITY_SET, opts.size(), max);
            if (d == null) {
                int[] a = askSet(g, p, opts, min, max, "chooseCardsForEffect");
                if (a == null) {
                    return null;
                }
                CardCollection out = new CardCollection();
                for (int i : a) {
                    out.add(opts.get(i));
                }
                return out;
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

    public static List<SpellAbility> forceOrderSa(Game g, Player p, List<SpellAbility> sas, String m) {
        try {
            SurfaceDirective d = SurfaceDirective.match(g, p, ORDER, sas.size(), sas.size());
            if (d == null) {
                int[] a = askOrder(g, p, sas, m);
                if (a == null) {
                    return null;
                }
                List<SpellAbility> out = new ArrayList<>(sas.size());
                for (int i : a) {
                    out.add(sas.get(i));
                }
                return out;
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

    public static CardCollection forceOrderCards(Game g, Player p, Iterable<Card> cards, String m) {
        try {
            List<Card> opts = asList(cards);
            SurfaceDirective d = SurfaceDirective.match(g, p, ORDER, opts.size(), opts.size());
            if (d == null) {
                int[] a = askOrder(g, p, opts, m);
                if (a == null) {
                    return null;
                }
                CardCollection out = new CardCollection();
                for (int i : a) {
                    out.add(opts.get(i));
                }
                return out;
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

    /** Ordering windows longer than this are never asked over the bridge (the
     *  option-set decoder's answer slots, anvil.policy.surfaces.SURF_MAX). */
    public static final int ORDER_MAX = 12;

    /** ORDER over the bridge (mtg.surface.order, evening 3): a permutation of
     *  the option indices; null = the natural line (not bridged, a trivial
     *  window, a window past ORDER_MAX, a declined or invalid answer). */
    static int[] askOrder(Game g, Player p, List<?> opts, String m) {
        AnvilBridge b = bridgeFor(p, ORDER);
        int n = opts.size();
        if (b == null || !nontrivial(ORDER, n, n) || n > ORDER_MAX) {
            return null;
        }
        int[] a = b.order(PlayerControllerAnvil.TAG_SURFACE_ORDER, labels(opts));
        boolean ok = a != null && validIndices(a, n, n, n, true);
        Census.rec(g, p, m, "by", "bridge", "n", n, "ok", ok);
        if (ok) {
            trace(g, p, ORDER, m, n, n, n, a, null); // the served answer = the natural line
        }
        return ok ? a : null;
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
            if (Census.loopTripped(g)) {
                return new java.util.ArrayList<>(possible.subList(0, Math.min(possible.size(), Math.max(0, min))));
            }
            SurfaceDirective d = SurfaceDirective.match(g, p, MODE, possible.size(), num);
            if (d == null) {
                int[] a = askMode(g, p, possible, min, num, allowRepeat, "chooseModeForAbility");
                if (a == null) {
                    return null;
                }
                List<AbilitySub> out = new ArrayList<>(a.length);
                for (int i : a) {
                    out.add(possible.get(i)); // a repeat is the same sub twice: CharmEffect clones each
                }
                return out;
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
            boolean allowRepeat, List<AbilitySub> chosen) {
        trace(g, p, MODE, labelOf(sa), possible == null ? 0 : possible.size(), min, num,
                indicesOf(asList(possible), chosen), null, allowRepeat);
    }

    /** MODE over the bridge (mtg.surface.mode): min..num option indices, a
     *  repeat allowed when the callback allows one; null = the natural line
     *  (not bridged, a trivial window, an invalid answer). */
    static int[] askMode(Game g, Player p, List<?> opts, int min, int max, boolean repeat, String m) {
        AnvilBridge b = bridgeFor(p, MODE);
        if (b == null || !nontrivial(MODE, opts.size(), max)
                || (!repeat && min >= opts.size() && max >= opts.size())) {
            return null;
        }
        int[] a = b.selectSet(PlayerControllerAnvil.TAG_SURFACE_MODE, labels(opts), min, max, repeat);
        boolean ok = a != null && validIndices(a, opts.size(), min, max, !repeat);
        // The playability gate (09-08, ADR-0105 addendum): the heuristic's
        // mode choice is a joint mode + target choice (CharmAi picks a mode
        // only when its play test passes), the head's is mode-only, and a
        // mode the engine's AI would not play gets the mandatory chooser's
        // targets at setupTargets — the −7pp on choose-one windows. When any
        // mode passes the play test the head's answer stands only if every
        // pick is a playable mode (else the natural line); when none passes
        // (the heuristic would decline — the Confluences) the head answers
        // freely. A stopgap until the model aims its own modes (targets as a
        // surface) or mainline surface acting values the answers.
        String gate = "free";
        if (ok) {
            Set<Integer> playable = playableModes(p, opts);
            // the gate applies only where the heuristic could fill the window's
            // minimum with playable modes (its own answer); below that it would
            // decline or force-fill — "choose two" with one playable mode fizzles
            // under the heuristic (the first smoke deferred 10 of 14 such windows)
            if (playable.size() >= Math.max(0, min)) {
                boolean within = true;
                for (int i : a) {
                    if (!playable.contains(i)) {
                        within = false;
                        break;
                    }
                }
                gate = within ? "pass" : "defer";
                ok = within;
            }
        }
        Census.rec(g, p, m, "by", "bridge", "n", opts.size(), "k", a == null ? -1 : a.length, "ok", ok, "gate", gate);
        if (ok) {
            trace(g, p, MODE, m, opts.size(), min, max, a, null, repeat); // the served answer = the natural line
        }
        return ok ? a : null;
    }

    /** The modes the engine's own AI would play (CharmAi's first pass:
     *  canPlaySa per mode, targets chosen as a side effect exactly as when
     *  the heuristic answers; setupTargets re-targets the chosen chain). */
    static Set<Integer> playableModes(Player p, List<?> opts) {
        Set<Integer> ok = new HashSet<>();
        try {
            forge.game.player.PlayerController pc = p.getController();
            if (!(pc instanceof forge.ai.PlayerControllerAi)) {
                return ok;
            }
            forge.ai.AiController aic = ((forge.ai.PlayerControllerAi) pc).getAi();
            for (int i = 0; i < opts.size(); i++) {
                Object o = opts.get(i);
                if (!(o instanceof AbilitySub)) {
                    continue;
                }
                AbilitySub sub = (AbilitySub) o;
                sub.setActivatingPlayer(p);
                if (forge.ai.AiPlayDecision.WillPlay == aic.canPlaySa(sub)) {
                    ok.add(i);
                }
            }
        } catch (Exception ignored) {
        }
        return ok;
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
                int[] a = askDamage(g, p, attacker, blockers, damageDealt, defender, overrideOrder,
                        "assignCombatDamage");
                return a == null ? null : damageMap(a, blockers, defender);
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
            return damageMap(d.answer, blockers, defender);
        } catch (Exception e) {
            return null;
        }
    }

    /** Amounts (per blocker + the defender's share) -> assignCombatDamage's map
     *  (a null key = the defending player, distributeAIDamage's convention). */
    static Map<Card, Integer> damageMap(int[] amounts, CardCollectionView blockers, GameEntity defender) {
        int n = blockers.size();
        Map<Card, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (amounts[i] > 0) {
                out.put(blockers.get(i), amounts[i]);
            }
        }
        if (amounts[n] > 0 && defender instanceof Card) {
            out.put((Card) defender, amounts[n]);
        } else if (amounts[n] > 0) {
            out.put(null, amounts[n]);
        }
        return out;
    }

    static boolean tramples(Card attacker, GameEntity defender) {
        try {
            return defender != null && attacker != null
                    && attacker.hasKeyword(forge.game.keyword.Keyword.TRAMPLE);
        } catch (Exception e) {
            return false;
        }
    }

    /** The damage window's option list for the dec record (evening 3): the
     *  blockers, then the defender when the attacker tramples (the answer may
     *  end on it: "the rest tramples over"). */
    public static List<Object> damageOpts(Card attacker, CardCollectionView blockers, GameEntity defender) {
        List<Object> out = new ArrayList<>();
        if (blockers != null) {
            for (Card c : blockers) {
                out.add(c);
            }
        }
        if (tramples(attacker, defender)) {
            out.add(defender);
        }
        return out;
    }

    /** Lethal damage per blocker (the dec record's "lethal": the loader's
     *  kill-order canonicalization of the heuristic's amounts). */
    public static List<Integer> lethalList(Player p, Card attacker, CardCollectionView blockers, int damage,
            GameEntity defender, boolean overrideOrder) {
        List<Integer> out = new ArrayList<>();
        if (blockers == null || blockers.isEmpty()) {
            return out;
        }
        int[] aux = lethalAux(p, attacker, blockers, damage, defender, overrideOrder);
        for (int i = 0; i < blockers.size(); i++) {
            out.add(aux[i]);
        }
        return out;
    }

    /** A kill-order sequence over blockers (0..n-1) and, last at most, the
     *  defender (n) -> amounts (length n + 1): lethal to each blocker in
     *  sequence while damage lasts; the remainder tramples over when the
     *  attacker tramples (distributeAIDamage's own rule) and lands on the
     *  last blocker picked otherwise; a defender pick closes the sequence
     *  early ("stop killing here, the rest tramples"). Null = an invalid
     *  sequence (empty, out of range, a repeat, the defender not last, the
     *  defender without trample). */
    public static int[] damageFromSequence(int[] seq, int n, int total, int[] aux) {
        if (seq == null || seq.length == 0 || seq.length > n + 1 || aux == null || aux.length != n + 1) {
            return null;
        }
        boolean trample = aux[n] != 0;
        Set<Integer> seen = new HashSet<>();
        for (int k = 0; k < seq.length; k++) {
            int i = seq[k];
            if (i < 0 || i > n || !seen.add(i)) {
                return null;
            }
            if (i == n && (!trample || k != seq.length - 1)) {
                return null;
            }
        }
        int[] a = new int[n + 1];
        int left = total;
        int last = -1;
        for (int i : seq) {
            if (left <= 0) {
                break;
            }
            if (i == n) {
                a[n] += left;
                left = 0;
                break;
            }
            int d = Math.min(left, Math.max(0, aux[i]));
            a[i] += d;
            left -= d;
            last = i;
        }
        if (left > 0) {
            if (trample) {
                a[n] += left;
            } else {
                a[last < 0 ? seq[0] : last] += left;
            }
        }
        return a;
    }

    /** DAMAGE over the bridge (mtg.surface.damage, evening 3): the model's
     *  kill order over blockers (+ the defender under trample) realized as
     *  amounts by the engine's own lethal arithmetic (damageFromSequence);
     *  null = the natural line. Only multi-blocker windows are decisions
     *  (nontrivial: n >= 2), as for the trace. */
    static int[] askDamage(Game g, Player p, Card attacker, CardCollectionView blockers, int damageDealt,
            GameEntity defender, boolean overrideOrder, String m) {
        AnvilBridge b = bridgeFor(p, DAMAGE);
        int n = blockers == null ? 0 : blockers.size();
        if (b == null || !nontrivial(DAMAGE, n, damageDealt) || n > ORDER_MAX) {
            return null;
        }
        List<Object> opts = damageOpts(attacker, blockers, defender);
        int[] seq = b.order(PlayerControllerAnvil.TAG_SURFACE_DAMAGE, labels(opts));
        int[] aux = lethalAux(p, attacker, blockers, damageDealt, defender, overrideOrder);
        int[] a = damageFromSequence(seq, n, damageDealt, aux);
        boolean ok = a != null;
        Census.rec(g, p, m, "by", "bridge", "n", n, "k", seq == null ? -1 : seq.length, "ok", ok);
        if (ok) {
            trace(g, p, DAMAGE, attacker == null ? "" : attacker.getName(), n, damageDealt, damageDealt, a, aux);
        }
        return a;
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
