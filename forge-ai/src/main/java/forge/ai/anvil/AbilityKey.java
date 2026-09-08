package forge.ai.anvil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import forge.game.CardTraitBase;
import forge.game.card.Card;
import forge.game.keyword.KeywordInterface;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/**
 * Anvil M12 Build 3 (ADR-0105): the ability's IDENTITY for the observation.
 *
 * <p>Every ability-bearing option entry ({@code {e, sa, kind}} on priority
 * windows and on the §3d′ surfaces) and every ability-bearing answer carries
 * {@code "ak"}: a 64-bit key of the ability's CANONICAL TEXT — the host card,
 * the trigger line, the keyword, the script parameters of the ability and its
 * sub-ability chain (the engine's own effect language: {@code Produced$ G} vs
 * {@code Produced$ Any}) and the full rules-text description — never the
 * 60-character display render the {@code sa} field keeps for the M1 vocab
 * join. The canonical text is what the pinned LLM embeds (text-hash keyed, one
 * table shared by priority candidates, surface options and stack entries);
 * the key is what the observation carries. A store session emits each key's
 * text once per game ({@code "abil"} on the dec record that first uses it);
 * {@code AnvilRun -abilities} dumps every pool card's abilities offline through
 * the same function, so the cache is built from the engine, not re-parsed.
 *
 * <p>Recording-only: nothing on the game path reads a key.
 */
public final class AbilityKey {
    private AbilityKey() {
    }

    /** Parameters that carry no effect semantics (AI hints, display copies). */
    private static final java.util.Set<String> DROP = new java.util.HashSet<>(java.util.Arrays.asList(
            "AILogic", "AIPreference", "AILogicIgnore", "AIPriority", "AITgts", "AiAttackPreference",
            "SpellDescription", "StackDescription", "PrecostDesc", "CostDesc", "Description",
            "UnlessAI", "AIPlayForSub", "AICheckSVar", "AISVarCompare", "AIRepeatSVar",
            "RememberAI", "AINoRecursiveCheck", "AICastPreference"));

    private static final ConcurrentHashMap<String, String> KEYS = new ConcurrentHashMap<>();
    /** Exceptions swallowed by enumerate() since the last reset (the dump reports it). */
    public static volatile int enumerateErrors = 0;

    public static final class Entry {
        public final String key;
        public final String host;
        public final String kind;
        public final String text;

        Entry(String key, String host, String kind, String text) {
            this.key = key;
            this.host = host;
            this.kind = kind;
            this.text = text;
        }
    }

    // ------------------------------------------------------------------
    // Canonical text

    /** The canonical text of an ability as it appears in play (a priority
     *  option, a triggered ability being ordered, a mode being chosen). */
    public static String canon(SpellAbility sa) {
        return canon(sa, sa == null ? null : sa.getTrigger());
    }

    /** trigger = the trigger the ability belongs to (in play: sa.getTrigger();
     *  in the offline dump: the state's Trigger whose overriding ability sa is). */
    public static String canon(SpellAbility sa, Trigger trigger) {
        if (sa == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        Card host = sa.getHostCard();
        sb.append("H:").append(host == null ? "?" : host.getName());
        try {
            Card origin = sa.getOriginalHost();
            if (origin != null && host != null && !origin.getName().equals(host.getName())) {
                sb.append(" <- ").append(origin.getName());
            }
        } catch (Exception ignored) {
        }
        sb.append('\n');
        if (trigger != null) {
            sb.append("T:").append(params(trigger)).append('\n');
        }
        try {
            KeywordInterface kw = sa.getKeyword();
            if (kw != null && kw.getOriginal() != null) {
                sb.append("K:").append(kw.getOriginal()).append('\n');
            }
        } catch (Exception ignored) {
        }
        try {
            AlternativeCost alt = sa.getAlternativeCost();
            if (alt != null) {
                sb.append("ALT:").append(alt.name()).append('\n');
            }
            int n = 0;
            for (OptionalCost oc : sa.getOptionalCosts()) {
                sb.append(n++ == 0 ? "OPT:" : ",").append(oc.name());
            }
            if (n > 0) {
                sb.append('\n');
            }
        } catch (Exception ignored) {
        }
        int link = 0;
        for (SpellAbility s = sa; s != null && link < 24; s = s.getSubAbility(), link++) {
            sb.append(link == 0 ? "A:" : "  A:");
            if (s.getApi() != null) {
                sb.append(s.getApi().name());
            } else if (s.isSpell()) {
                sb.append("Spell");
            } else {
                sb.append("?");
            }
            String p = params(s);
            if (!p.isEmpty()) {
                sb.append(" | ").append(p);
            }
            sb.append('\n');
        }
        String d = null;
        try {
            d = sa.getDescription();
        } catch (Exception ignored) {
        }
        if (d == null || d.isEmpty()) {
            d = String.valueOf(sa);
        }
        sb.append("D:").append(stripRuntime(d));
        return sb.toString();
    }

    /** Evening 2 (09-07): the description of an ability IN PLAY carries
     *  runtime state the static text never has — a triggered ability on the
     *  stack appends its run parameters as a bracketed "Key: value" list
     *  ("[Damage Source: Warrior Token (208), Amount: 1]", nested for
     *  attacker lists), a granted or copied ability appends " by <source>
     *  (<id>)" — so every instance hashed to a new key (10,325 store-only
     *  keys in 1,000 games, 7 of 10 of them this class). Strip exactly those
     *  shapes; static brackets ("Prototype {1}{W}{W} [3/3]") carry no ": "
     *  and stay, so every pool-dump key is byte-identical (verified by
     *  re-dumping the pool). */
    public static String stripRuntime(String d) {
        String s = d.replace("\r\n", "\n").trim();
        while (s.endsWith("]")) {
            int depth = 0;
            int i = s.length() - 1;
            for (; i >= 0; i--) {
                char c = s.charAt(i);
                if (c == ']') {
                    depth++;
                } else if (c == '[') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            if (i < 0 || !s.substring(i).contains(": ")) {
                break;
            }
            s = s.substring(0, i).trim();
        }
        s = s.replaceAll("(\\s*\\bby\\s+[^()\\[\\]\\n]{1,80}\\(\\d+\\))+$", "").trim();
        return s;
    }

    /** A static ability or replacement effect (the dump's S:/R: lines; not
     *  yet an option kind on the wire). */
    public static String canonTrait(CardTraitBase t, String tag) {
        StringBuilder sb = new StringBuilder(160);
        Card host = t.getHostCard();
        sb.append("H:").append(host == null ? "?" : host.getName()).append('\n');
        sb.append(tag).append(':').append(params(t)).append('\n');
        String d = null;
        try {
            d = t.getParam("Description");
        } catch (Exception ignored) {
        }
        if (d == null) {
            d = String.valueOf(t);
        }
        sb.append("D:").append(d.replace("\r\n", "\n").trim());
        return sb.toString();
    }

    static String params(CardTraitBase t) {
        Map<String, String> m = null;
        try {
            m = t.getOriginalMapParams();
            if (m == null || m.isEmpty()) {
                m = t.getMapParams();
            }
        } catch (Exception ignored) {
        }
        if (m == null || m.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() == null || DROP.contains(e.getKey())) {
                continue;
            }
            sorted.put(e.getKey(), e.getValue() == null ? "" : e.getValue().replace("\r\n", " ").replace('\n', ' '));
        }
        StringBuilder sb = new StringBuilder(128);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(e.getKey()).append('$').append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Keys

    /** 64-bit key (16 hex chars) of the canonical text — SHA-256 truncated,
     *  stable across JVMs and runs. */
    public static String key(String canon) {
        String k = KEYS.get(canon);
        if (k != null) {
            return k;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(canon.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((h[i] >> 4) & 0xF, 16)).append(Character.forDigit(h[i] & 0xF, 16));
            }
            k = sb.toString();
        } catch (Exception e) {
            k = Long.toHexString(canon.hashCode() & 0xFFFFFFFFL);
        }
        if (KEYS.size() < 200_000) {
            KEYS.put(canon, k);
        }
        return k;
    }

    public static String kind(SpellAbility sa) {
        if (sa == null) {
            return "other";
        }
        if (sa.isTrigger()) {
            return "trigger";
        }
        if (sa instanceof AbilitySub) {
            return "sub";
        }
        if (sa.isSpell()) {
            return "spell";
        }
        if (sa.isManaAbility()) {
            return "mana";
        }
        if (sa.isActivatedAbility()) {
            return "ability";
        }
        return "other";
    }

    /** The key of an ability in play, registering its text with the host's
     *  game session (Obs emits new texts once per game). Null for a hostless
     *  ability. Never throws. */
    public static String note(SpellAbility sa) {
        try {
            Card host = sa == null ? null : sa.getHostCard();
            if (host == null) {
                return null;
            }
            String c = canon(sa);
            String k = key(c);
            Obs.noteAbility(host.getGame(), k, host.getName(), kind(sa), c);
            return k;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Offline enumeration (AnvilRun -abilities)

    /** Every ability of a card object, all faces: spells, activated and mana
     *  abilities (keyword-generated included), each trigger's ability,
     *  additional abilities and lists (modes), statics and replacements. */
    public static List<Entry> enumerate(Card c) {
        List<Entry> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (forge.card.CardStateName stName : new ArrayList<>(c.getStates())) {
            forge.game.card.CardState st = c.getState(stName);
            if (st == null) {
                continue;
            }
            try {
                for (SpellAbility sa : st.getSpellAbilities()) {
                    addSa(out, seen, sa, null, c.getName());
                }
            } catch (Exception e) {
                enumerateErrors++;
            }
            try {
                for (Trigger t : st.getTriggers()) {
                    SpellAbility sa = t.getOverridingAbility();
                    if (sa != null) {
                        addSa(out, seen, sa, t, c.getName());
                    }
                }
            } catch (Exception e) {
                enumerateErrors++;
            }
            try {
                for (forge.game.staticability.StaticAbility s : st.getStaticAbilities()) {
                    add(out, seen, canonTrait(s, "S"), c.getName(), "static");
                }
            } catch (Exception e) {
                enumerateErrors++;
            }
            try {
                for (forge.game.replacement.ReplacementEffect r : st.getReplacementEffects()) {
                    add(out, seen, canonTrait(r, "R"), c.getName(), "replacement");
                    SpellAbility sa = r.getOverridingAbility();
                    if (sa != null) {
                        addSa(out, seen, sa, null, c.getName());
                    }
                }
            } catch (Exception e) {
                enumerateErrors++;
            }
        }
        return out;
    }

    private static void addSa(List<Entry> out, java.util.Set<String> seen, SpellAbility sa, Trigger t, String host) {
        add(out, seen, canon(sa, t), host, t != null ? "trigger" : kind(sa));
        try {
            for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                for (SpellAbility a : s.getAdditionalAbilities().values()) {
                    if (a != null) {
                        addSa(out, seen, a, null, host);
                    }
                }
                for (List<AbilitySub> l : s.getAdditionalAbilityLists().values()) {
                    for (AbilitySub a : l) {
                        if (a != null) {
                            addSa(out, seen, a, null, host);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void add(List<Entry> out, java.util.Set<String> seen, String canon, String host, String kind) {
        String k = key(canon);
        if (seen.add(k)) {
            out.add(new Entry(k, host, kind, canon));
        }
    }
}
