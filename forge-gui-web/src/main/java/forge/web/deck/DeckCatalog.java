package forge.web.deck;

import com.google.common.collect.MapMaker;
import com.google.common.primitives.Ints;
import forge.StaticData;
import forge.card.CardEdition;
import forge.deck.ArchetypeDeckGenerator;
import forge.deck.CardPool;
import forge.deck.CommanderBracketCalculator;
import forge.deck.CommanderDeckGenerator;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckSection;
import forge.deck.DeckStatistics;
import forge.deck.DeckUrlLoader;
import forge.deck.DeckgenUtil;
import forge.deck.NetDeckCategory;
import forge.deck.io.DeckPreferences;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.gamemodes.quest.QuestController;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.SleeveArt;
import forge.web.protocol.FromBrowser.DeckQueryKind;
import forge.web.protocol.ToBrowser.Bracket;
import forge.web.protocol.ToBrowser.BracketReason;
import forge.web.protocol.ToBrowser.BracketReasonKind;
import forge.web.protocol.ToBrowser.DeckDetails;
import forge.web.protocol.ToBrowser.DeckStats;
import forge.web.protocol.ToBrowser.DeckSummary;
import forge.web.protocol.ToBrowser.SavedSleeveArt;
import forge.web.protocol.ToBrowser.TypeCount;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/** The decks a seat may choose from, and what the browser shows about each one. */
public final class DeckCatalog {
    /** A deck's source, which the browser shows as a tag on each row. */
    static final String MINE = "yours";
    /** A guest's decks, stored in the guest's browser and keyed by the id it stores them under. */
    static final String DEVICE = "device";
    static final String LINKED = "linked";
    static final String EVENT = "event";
    private static final String PRECON = "precons";
    /** Sources that build a deck when picked, instead of loading a saved deck. */
    private static final String GENERATED = "generated";
    private static final String COMMANDER_GEN = "gen:commander:";
    private static final String NET = "net";
    /** A colour generator's colour is known before it builds a deck. The other generators' colours are not. */
    private static final Map<String, String> COLOUR_LETTERS = Map.of(
            "White", "W", "Blue", "U", "Black", "B", "Red", "R", "Green", "G");
    /** The finder lists a deck's cards without problem marks. The verdict line states the problems. */
    public static final Legality.Result NO_FLAGS = new Legality.Result(Map.of(), Map.of(), Set.of(), List.of());
    /** Kept per deck object, not per contents, because working out a deck's formats reads every printing of its cards. */
    private static final Map<Deck, Facts> FACTS = new MapMaker().weakKeys().makeMap();
    private static final Map<Deck, Map<String, Optional<String>>> VERDICTS = new MapMaker().weakKeys().makeMap();
    /** A Commander deck's bracket, cached the same way. It is worked out only for decks listed in Commander. */
    private static final Map<Deck, Integer> BRACKETS = new MapMaker().weakKeys().makeMap();
    /** Held while any code reads or changes a deck. A deck loads its sections on first read, and that is not safe from two threads. */
    public static final Object DECKS = new Object();
    /** The most cards a problem sentence names before it counts the rest. */
    private static final int NAMED_CARDS = 3;

    private final Map<String, Entry> byKey = new ConcurrentHashMap<>();
    /** The categories that the browser asked for and core downloaded. They are kept so a refresh does not lose them. */
    private final List<NetDeckCategory> netCategories = new ArrayList<>();
    /** The card pool of the last refresh. Generators build from it, and every verdict checks against it. */
    private volatile GameFormat pool;
    /** Whether this catalogue serves a guest's browser. A guest's own decks are the ones on its device. */
    private boolean guest;

    /** A generator's deck is built when first requested and then kept, so the panel and the match see the same cards. */
    private record Entry(DeckProxy proxy, boolean generated, Deck built) {
        Entry(final DeckProxy proxy) {
            this(proxy, false, null);
        }
    }

    /** The sanctioned formats among those a deck is legal in, in the sanctioned list's order. */
    private static List<String> legalIn(final Set<GameFormat> formats) {
        final List<String> out = new ArrayList<>();
        for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
            if (formats.contains(f)) {
                out.add(f.getName());
            }
        }
        return out;
    }

    static void changed(final Deck deck) {
        FACTS.remove(deck);
        VERDICTS.remove(deck);
        BRACKETS.remove(deck);
    }

    /** A deck's suggested minimum Commander bracket, 1 to 4. */
    public static int bracketOf(final Deck deck) {
        return BRACKETS.computeIfAbsent(deck, CommanderBracketCalculator::getBracket);
    }

    /** The bracket shown in a deck's list row. Only Commander shows it, because brackets apply only to that format. */
    private static Integer listedBracket(final Deck deck, final GameType format) {
        return format == GameType.Commander ? bracketOf(deck) : null;
    }

    /** The bracket and every kind of card that raised it, for the panel next to the list. */
    public static Bracket bracket(final Deck deck) {
        final CommanderBracketCalculator.Result r = CommanderBracketCalculator.calculate(deck);
        final Localizer text = Localizer.getInstance();
        final List<BracketReason> reasons = new ArrayList<>();
        final List<String> clear = new ArrayList<>();
        final int changers = r.getGamechangers().size();
        reason(reasons, clear, BracketReasonKind.gameChangers, text.getMessage("lblCommanderBracketGameChangers"),
                text.getMessage("lblWebBracketBriefGameChangers", changers), r.getGamechangers(), r.getGamechangersBracket(),
                text.getMessage(r.getGamechangersBracket() == 4 ? "lblCommanderBracketReasonGameChangersFour" : "lblCommanderBracketReasonGameChangersOne"));
        reason(reasons, clear, BracketReasonKind.massLandDenial, text.getMessage("lblCommanderBracketMassLandDenial"),
                text.getMessage("lblCommanderBracketMassLandDenial"), r.getMassLandDenial(), r.getMassLandDenialBracket(),
                text.getMessage("lblCommanderBracketReasonMassLandDenial"));
        final int turns = r.getExtraTurns().size();
        reason(reasons, clear, BracketReasonKind.extraTurns, text.getMessage("lblCommanderBracketExtraTurns"),
                text.getMessage("lblWebBracketBriefExtraTurns", turns), r.getExtraTurns(), r.getExtraTurnsBracket(),
                text.getMessage(switch (r.getExtraTurnsBracket()) {
                    case 4 -> "lblCommanderBracketReasonExtraTurnsFour";
                    case 3 -> "lblCommanderBracketReasonExtraTurnsThree";
                    case 2 -> "lblCommanderBracketReasonExtraTurnsTwo";
                    default -> "lblCommanderBracketReasonExtraTurnsFew";
                }));
        reason(reasons, clear, BracketReasonKind.chainedExtraTurns, text.getMessage("lblCommanderBracketChainedExtraTurns"),
                text.getMessage("lblCommanderBracketChainedExtraTurns"), r.getChainedExtraTurns(), r.getChainedExtraTurnsBracket(),
                text.getMessage("lblCommanderBracketReasonChainedExtraTurn"));
        final List<String> late = r.getLateGameCombos().stream().map(Object::toString).toList();
        final List<String> early = r.getEarlyGameCombos().stream().map(Object::toString).toList();
        final String combos = text.getMessage("lblCommanderBracketTwoCardCombos");
        if (late.isEmpty() && early.isEmpty()) {
            clear.add(combos);
        }
        if (!late.isEmpty()) {
            reasons.add(new BracketReason(BracketReasonKind.lateCombos, combos + " · " + text.getMessage("lblCommanderBracketLateGame"),
                    text.getMessage("lblWebBracketBriefLateCombo"), late, r.getLateGameCombosBracket(), text.getMessage("lblCommanderBracketReasonLateGameCombo")));
        }
        if (!early.isEmpty()) {
            reasons.add(new BracketReason(BracketReasonKind.earlyCombos, combos + " · " + text.getMessage("lblCommanderBracketEarlyGame"),
                    text.getMessage("lblWebBracketBriefEarlyCombo"), early, r.getEarlyGameCombosBracket(), text.getMessage("lblCommanderBracketReasonEarlyGameCombo")));
        }
        reasons.sort(Comparator.comparingInt(BracketReason::raises).reversed());
        return new Bracket(r.getBracket(), reasons, clear);
    }

    private static void reason(final List<BracketReason> reasons, final List<String> clear, final BracketReasonKind kind, final String title,
            final String brief, final List<String> cards, final int raises, final String why) {
        if (cards.isEmpty()) {
            clear.add(title);
        } else {
            reasons.add(new BracketReason(kind, title, brief, cards, raises, why));
        }
    }

    private static Facts factsOf(final Deck deck, final DeckProxy proxy) {
        return FACTS.computeIfAbsent(deck, d -> new Facts(colors(d), played(d), count(d.get(DeckSection.Sideboard)),
                legalIn(proxy != null ? proxy.getFormats() : FModel.getFormats().getAllFormatsOfDeck(d)),
                proxy == null ? null : proxy.getFormatsString(), d.getAverageCMC()));
    }

    /** The same verdict as problem, cached for each deck until it is saved. */
    public static String problemAt(final Deck deck, final GameType format, final GameFormat pool) {
        final String table = format.name() + "|" + (pool == null ? "" : pool.getName()) + "|"
                + FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY);
        return VERDICTS.computeIfAbsent(deck, d -> new ConcurrentHashMap<>())
                .computeIfAbsent(table, t -> Optional.ofNullable(problem(deck, format, pool))).orElse(null);
    }

    /** Asks core for a net deck category. Core picks one through the browser and downloads it. */
    public void loadNetDecks(final GameType format) {
        // Picking waits for the host's answer, so the decks are locked only to record the pick
        final NetDeckCategory category = NetDeckCategory.selectAndLoad(format);
        synchronized (DECKS) {
            if (category != null && !netCategories.contains(category)) {
                netCategories.add(category);
            }
        }
    }

    /** Rebuilds the catalogue and returns its decks. At a Draft table these are event pools. When event is not null, only that event's pools are listed. */
    public List<DeckSummary> refresh(final GameType format, final GameFormat pool, final boolean guest, final Map<String, OnDevice> device,
            final String event) {
        synchronized (DECKS) {
            this.guest = guest;
            // A built generator deck stays built while the pool is unchanged, so the list agrees with the seat that holds it
            final Map<String, Entry> built = new HashMap<>();
            if (pool == this.pool) {
                byKey.forEach((key, e) -> {
                    if (e.built() != null) {
                        built.put(key, e);
                    }
                });
            }
            this.pool = pool;
            byKey.clear();
            final List<DeckSummary> out = new ArrayList<>();
            // Momir Basic and MoJhoSto deal their own decks, so there are none to list
            if (format.isAutoGenerated()) {
                return out;
            }
            if (format == GameType.Draft) {
                if (!guest) {
                    add(out, format, DeckProxy.getAllNetworkEventDecks().stream()
                            .filter(p -> event == null || event.equals(DeckProxy.getEventTag(p.getDeck(), "eventId"))).toList(), EVENT);
                }
                device.forEach((id, d) -> {
                    if (DeckStore.family(d.format()) == GameType.Draft
                            && (event == null || event.equals(DeckProxy.getEventTag(d.deck(), "eventId")))) {
                        addDevice(out, format, id, d.deck());
                    }
                });
                return out;
            }
            // Each commander format has its own decks. Only Commander has its own precons
            add(out, format, switch (format) {
                case Commander -> DeckProxy.getAllCommanderDecks();
                case Oathbreaker -> DeckProxy.getAllOathbreakerDecks();
                case Brawl -> DeckProxy.getAllBrawlDecks();
                case TinyLeaders -> DeckProxy.getAllTinyLeadersDecks();
                default -> DeckProxy.getAllConstructedDecks();
            }, MINE);
            if (format == GameType.Commander) {
                add(out, format, DeckProxy.getAllCommanderPreconDecks(), PRECON);
            }
            if (format == GameType.Commander || format == GameType.Oathbreaker || format == GameType.Brawl || format == GameType.TinyLeaders) {
                addCommanderGenerators(out, format);
            } else if (format == GameType.Constructed) {
                add(out, format, DeckProxy.getAllPreconstructedDecks(QuestController.getPrecons()), PRECON);
                addGenerators(out);
            }
            for (final NetDeckCategory category : netCategories) {
                add(out, format, DeckProxy.getNetDecks(category), NET + " " + category.getName());
            }
            if (!guest) {
                add(out, format, linkedDecks(format), LINKED);
            }
            device.forEach((id, d) -> {
                if (DeckStore.family(d.format()) == DeckStore.family(format)) {
                    addDevice(out, format, id, d.deck());
                }
            });
            built.forEach((key, e) -> byKey.computeIfPresent(key, (k, fresh) -> e));
            return out;
        }
    }

    public Deck deck(final String key) {
        synchronized (DECKS) {
            final Entry e = key == null ? null : byKey.get(key);
            if (e == null) {
                return null;
            }
            // A generator has no deck until one is requested, and it builds a different deck each time
            if (e.built() != null) {
                return e.built();
            }
            final Deck deck = e.proxy() != null ? e.proxy().getDeck() : key.startsWith(COMMANDER_GEN) ? buildCommander(key) : buildColours(key, pool);
            if (e.generated()) {
                byKey.put(key, new Entry(e.proxy(), true, deck));
            }
            return deck;
        }
    }

    /** Replaces the cached copy. Each save makes a new deck object, and a seat must be given that new object. */
    public String adopt(final String tag, final String path, final Deck deck) {
        final String key = DEVICE.equals(tag) ? DEVICE + ":" + path : tag + ":" + path + "/" + deck.getName();
        synchronized (DECKS) {
            byKey.put(key, new Entry(null, false, deck));
        }
        return key;
    }

    /** Whether the deck behind a key cannot be changed in place. A guest may change only the decks in its own browser. */
    static boolean readOnly(final String key, final boolean guest) {
        final String tag = key.substring(0, Math.max(0, key.indexOf(':')));
        return guest ? !DEVICE.equals(tag) : !MINE.equals(tag);
    }

    /** The folder path in a key, or "" for a deck at the top of its format's folder. */
    static String pathOf(final String key) {
        final int colon = key.indexOf(':');
        final int slash = key.lastIndexOf('/');
        return colon < 0 || slash <= colon ? "" : key.substring(colon + 1, slash);
    }

    /** Builds a deck around a random commander, as desktop's Random button picks from its list of every legal commander. */
    private static Deck buildCommander(final String key) {
        // gen:commander:<format>:<random or cardgen>
        final String[] parts = key.split(":");
        // Built so the computer can play it, because the key does not say who will play it. A person can play any deck the computer can
        final List<DeckProxy> legends = CommanderDeckGenerator.getCommanderDecks(GameType.valueOf(parts[2]).getDeckFormat(), true,
                "cardgen".equals(parts[3]));
        return legends.isEmpty() ? null : legends.get(MyRandom.getRandom().nextInt(legends.size())).getDeck();
    }

    /** Builds the colour generator deck for a "gen:color:" key, from the chosen card pool when there is one. */
    private static Deck buildColours(final String key, final GameFormat pool) {
        final List<String> selection = List.of(key.substring(key.lastIndexOf(':') + 1));
        return DeckgenUtil.colorCheck(selection)
                ? DeckgenUtil.buildColorDeck(selection, pool == null ? null : pool.getFilterPrinted(), false) : null;
    }

    /** Writes a card-art sleeve onto a deck and saves it, the way the desktop lobby does. */
    public boolean saveSleeveArt(final String key, final String imageKey, final int offset) {
        synchronized (DECKS) {
            final Entry e = key == null ? null : byKey.get(key);
            final Deck deck = e == null || e.proxy() == null ? null : e.proxy().getDeck();
            if (deck == null) {
                return false;
            }
            deck.setSleeveArtKey(imageKey);
            deck.setSleeveArtOffset(SleeveArt.clampOffset(offset));
            rememberSleeveArt(imageKey, offset);
            return e.proxy().saveDeck();
        }
    }

    /** The chosen deck's card list, grouped as a decklist is read, for the panel next to the results. */
    public DeckDetails details(final String key, final GameType format) {
        synchronized (DECKS) {
            final Deck deck = deck(key);
            if (deck == null) {
                return null;
            }
            return new DeckDetails(key, deck.getName(), problem(deck, format, pool), colors(deck), stats(deck),
                    CardCatalog.groups(deck.getMain(), NO_FLAGS), CardCatalog.cards(deck.get(DeckSection.Sideboard), NO_FLAGS),
                    deck.getSleeveArtKey(),
                    deck.getSleeveArtOffset(),
                    format == GameType.Commander ? bracket(deck) : null, aiSkipNames(deck));
        }
    }

    /** The keys of listed decks that hold a matching card. A card matches by name outside or inside the sideboard, or by set code or set name. */
    public List<String> matching(final DeckQueryKind kind, final String value) {
        final String words = value.trim().toLowerCase();
        final CardEdition set = kind == DeckQueryKind.set ? edition(value.trim()) : null;
        final List<String> out = new ArrayList<>();
        if (words.isEmpty() || kind == DeckQueryKind.set && set == null) {
            return out;
        }
        synchronized (DECKS) {
            byKey.forEach((key, e) -> {
                final Deck deck = e.generated() ? null : e.built() != null ? e.built() : e.proxy() == null ? null : e.proxy().getDeck();
                if (deck == null) {
                    return;
                }
                for (final Map.Entry<DeckSection, CardPool> section : deck) {
                    if ((kind == DeckQueryKind.sideboard) != (section.getKey() == DeckSection.Sideboard) && kind != DeckQueryKind.set) {
                        continue;
                    }
                    if (section.getValue().find(c -> set != null ? set.getCode().equals(c.getEdition())
                            : c.getName().toLowerCase().contains(words)) != null) {
                        out.add(key);
                        return;
                    }
                }
            });
        }
        return out;
    }

    /** A set by its code, any of its aliases, or its name. */
    private static CardEdition edition(final String codeOrName) {
        final CardEdition.Collection editions = StaticData.instance().getEditions();
        final CardEdition byCode = editions.get(codeOrName.toUpperCase());
        return byCode != null ? byCode
                : editions.stream().filter(e -> e.getName().equalsIgnoreCase(codeOrName)).findFirst().orElse(null);
    }

    /** The deck's colour identity as WUBRG letters, or "C" when it has none. */
    public static String colors(final Deck deck) {
        return CardCatalog.letters(DeckProxy.getColorIdentity(deck));
    }

    /** Stars a listed deck the way desktop does, in the deck preferences that both clients read. */
    public void setFavourite(final String key, final boolean on) {
        final Entry e = key == null ? null : byKey.get(key);
        if (e == null || e.proxy() == null) {
            return;
        }
        DeckPreferences.getPrefs(e.proxy()).setStarCount(on ? 1 : 0);
    }

    /** What the panel shows about a deck next to its card list. The types use the editor's own headings, so the two agree. */
    public static DeckStats stats(final Deck deck) {
        final CardPool main = deck.get(DeckSection.Main);
        final DeckStatistics shared = DeckStatistics.fromDeck(deck);
        final int[] curve = new int[DeckStatistics.CURVE_BUCKETS];
        for (int i = 0; i < curve.length; i++) {
            curve[i] = shared.permanentCurve[i] + shared.spellCurve[i];
        }
        final Map<String, Integer> types = new LinkedHashMap<>();
        if (main != null) {
            main.forEach(e -> types.merge(CardCatalog.heading(e.getKey()), e.getValue(), Integer::sum));
        }
        return new DeckStats(played(deck), count(main), count(deck.get(DeckSection.Sideboard)), shared.landCount,
                Math.round(shared.averageCmc * 100) / 100f, Ints.asList(curve), Ints.asList(shared.spellCurve),
                types.entrySet().stream().map(e -> new TypeCount(e.getKey(), e.getValue())).toList(), Ints.asList(shared.pips));
    }

    /** Generators are listed by name only. Building a deck to fill each row would build hundreds of decks. */
    private void addGenerators(final List<DeckSummary> out) {
        // The names the colour generator expects, shown with friendlier labels
        final Map<String, String> colours = new LinkedHashMap<>();
        final Localizer text = Localizer.getInstance();
        colours.put("Random 1", text.getMessage("lblWebDeckCatalogRandomOne"));
        colours.put("Random 2", text.getMessage("lblWebDeckCatalogRandomTwo"));
        colours.put("Random 3", text.getMessage("lblWebDeckCatalogRandomThree"));
        colours.put("White", text.getMessage("lblWhite"));
        colours.put("Blue", text.getMessage("lblBlue"));
        colours.put("Black", text.getMessage("lblBlack"));
        colours.put("Red", text.getMessage("lblRed"));
        colours.put("Green", text.getMessage("lblGreen"));
        for (final Map.Entry<String, String> c : colours.entrySet()) {
            final String key = "gen:color:" + c.getKey();
            byKey.put(key, new Entry(null, true, null));
            generated(out, key, c.getValue(), text.getMessage("lblWebDeckCatalogBuiltOnPick"), COLOUR_LETTERS.getOrDefault(c.getKey(), ""));
        }
        // A theme deck is a fixed list, so it cannot be limited to a card pool
        if (pool == null) {
            for (final DeckProxy theme : DeckProxy.getAllThemeDecks()) {
                byKey.put("gen:theme:" + theme.getName(), new Entry(theme, true, null));
                generated(out, "gen:theme:" + theme.getName(), theme.getName(), text.getMessage("lblWebDeckCatalogThemeDeck"), "");
            }
        }
        if (FModel.isdeckGenMatrixLoaded()) {
            for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
                if (pool != null && f != pool) {
                    continue;
                }
                for (final DeckProxy archetype : ArchetypeDeckGenerator.getMatrixDecks(f, false)) {
                    final String key = "gen:archetype:" + f.getName() + ":" + archetype.getName();
                    byKey.put(key, new Entry(archetype, true, null));
                    generated(out, key, archetype.getName(), text.getMessage("lblWebDeckCatalogArchetype", f.getName()), "");
                }
            }
        }
    }

    private void addCommanderGenerators(final List<DeckSummary> out, final GameType format) {
        final Localizer text = Localizer.getInstance();
        final List<String> kinds = new ArrayList<>(List.of("random"));
        // The card-based generator reads data that Forge learned from real decks. That data loads at startup only when the settings ask for it
        if (FModel.isdeckGenMatrixLoaded()) {
            kinds.add("cardgen");
        }
        for (final String kind : kinds) {
            final String key = COMMANDER_GEN + format.name() + ":" + kind;
            byKey.put(key, new Entry(null, true, null));
            generated(out, key, text.getMessage("random".equals(kind) ? "lblWebDeckCatalogRandomCommander" : "lblWebDeckCatalogCardBasedCommander"),
                    text.getMessage("lblWebDeckCatalogBuiltOnPick"), "");
        }
    }

    // Colours are empty when they are not known until the deck is built. A colour filter treats empty colours as no match
    private static void generated(final List<DeckSummary> out, final String key, final String name, final String note,
            final String colours) {
        out.add(new DeckSummary(key, name, GENERATED, colours, true, note, null, null, null, null, null, null, null, true,
                null, null, null, null, null, null, null));
    }

    private void add(final List<DeckSummary> out, final GameType format, final Iterable<DeckProxy> source, final String tag) {
        for (final DeckProxy proxy : source) {
            final String key = tag + ":" + proxy.getPath() + "/" + proxy.getName();
            byKey.put(key, new Entry(proxy));
            final Deck deck = proxy.getDeck();
            final Facts facts = factsOf(deck, proxy);
            // An illegal deck is listed with its problem, and the finder's legal-only filter decides whether it is shown
            final boolean linked = LINKED.equals(tag);
            out.add(new DeckSummary(key, proxy.getName(), tag, facts.colors(), null, null, facts.played(),
                    facts.sideboard(), problemAt(deck, format, pool), facts.legalIn(), facts.formats(),
                    deck.getSleeveArtKey(), deck.getSleeveArtOffset(), readOnly(key, guest),
                    linked ? site(deck.getSourceUrl()) : null, linked ? deck.getSourceUrl() : null,
                    linked ? linkedFile(deck).lastModified() : null, listedBracket(deck, format), facts.averageMana(),
                    proxy.isFavoriteDeck() ? true : null, aiSkips(deck)));
        }
    }

    private void addDevice(final List<DeckSummary> out, final GameType format, final String id, final Deck deck) {
        final String key = DEVICE + ":" + id;
        byKey.put(key, new Entry(null, false, deck));
        final Facts facts = factsOf(deck, null);
        out.add(new DeckSummary(key, deck.getName(), DEVICE, facts.colors(), null, null, facts.played(),
                facts.sideboard(), problemAt(deck, format, pool), facts.legalIn(), null,
                deck.getSleeveArtKey(), deck.getSleeveArtOffset(), false, null, null, null, listedBracket(deck, format),
                facts.averageMana(), null, aiSkips(deck)));
    }

    private static List<String> aiSkipNames(final Deck deck) {
        final List<? extends PaperCard> skipped = deck.getUnplayableAICards().unplayable.get(DeckSection.Main);
        return skipped == null ? null : skipped.stream().map(PaperCard::getName).distinct().toList();
    }

    private static Integer aiSkips(final Deck deck) {
        final int skipped = deck.getUnplayableAICards().inMainDeck;
        return skipped == 0 ? null : skipped;
    }

    /** The decks loaded from links that belong to this format. A deck uses the format it records, or Commander when it has a commander. */
    private static List<DeckProxy> linkedDecks(final GameType format) {
        final List<DeckProxy> out = new ArrayList<>();
        for (final DeckProxy proxy : DeckUrlLoader.getUrlDecks()) {
            final Deck deck = proxy.getDeck();
            final GameType family = deck.getDeckFormat() != null ? DeckStore.family(deck.getDeckFormat())
                    : deck.has(DeckSection.Commander) ? GameType.Commander : GameType.Constructed;
            if (family == DeckStore.family(format)) {
                out.add(proxy);
            }
        }
        return out;
    }

    /** The site a linked deck came from, spelled as the site spells its own name. */
    static String site(final String url) {
        final String host = url == null ? "" : url.toLowerCase();
        if (host.contains("moxfield.com")) {
            return "Moxfield";
        }
        if (host.contains("archidekt.com")) {
            return "Archidekt";
        }
        if (host.contains("tappedout.net")) {
            return "TappedOut";
        }
        return host.contains("mtggoldfish.com") ? "MTGGoldfish" : Localizer.getInstance().getMessage("lblWebDeckCatalogTheLink");
    }

    // Loading a link again rewrites its file, so the file's time is when the deck was last synced
    private static File linkedFile(final Deck deck) {
        return new File(ForgeConstants.DECK_BASE_DIR + "URL" + ForgeConstants.PATH_SEPARATOR + deck.getBestFileName() + ".dck");
    }

    /** Why this deck cannot be played here, or null when it can. A chosen card pool is checked even with deck legality checks off. */
    public static String problem(final Deck deck, final GameType format, final GameFormat pool) {
        if (deck == null) {
            return Localizer.getInstance().getMessage("lblWebDeckCatalogNoDeck");
        }
        final String outOfPool = pool == null ? null : poolProblem(pool, deck);
        if (outOfPool != null || !FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            return outOfPool;
        }
        return format.getDeckFormat().getDeckConformanceProblem(deck);
    }

    /** The pool's verdict on a deck as one sentence. It names the cards that fail GameFormat's two checks, in GameFormat's order. */
    static String poolProblem(final GameFormat pool, final Deck deck) {
        if (pool.getDeckConformanceProblem(deck) == null) {
            return null;
        }
        final CardPool cards = deck.getAllCardsInASinglePool();
        final List<String> illegal = namesIn(cards, (card, count) -> !pool.getFilterRules().test(card));
        if (!illegal.isEmpty()) {
            return Localizer.getInstance().getMessage(illegal.size() == 1 ? "lblWebDeckCatalogNotLegalOne" : "lblWebDeckCatalogNotLegal",
                    pool.getName(), illegal.size(), listOf(illegal));
        }
        final boolean legendsRestricted = Boolean.TRUE.equals(pool.isRestrictedLegendary());
        final List<String> restricted = namesIn(cards, (card, count) -> count > 1 && (pool.getRestrictedCards().contains(card.getName())
                || legendsRestricted && card.getRules().getType().isLegendary() && !card.getRules().getType().isPlaneswalker()));
        return Localizer.getInstance().getMessage("lblWebDeckCatalogAllowsOneOf", pool.getName(), listOf(restricted));
    }

    /** The names of the matching cards. Each name appears once, even when the pool holds several printings. */
    private static List<String> namesIn(final CardPool cards, final BiPredicate<PaperCard, Integer> matches) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Map.Entry<PaperCard, Integer> entry : cards) {
            if (matches.test(entry.getKey(), entry.getValue())) {
                names.add(entry.getKey().getDisplayName());
            }
        }
        return new ArrayList<>(names);
    }

    /** "A", "A and B", "A, B and C", or "A, B, C and 4 more". */
    private static String listOf(final List<String> names) {
        final List<String> shown = names.subList(0, Math.min(NAMED_CARDS, names.size()));
        final int more = names.size() - shown.size();
        if (more > 0) {
            return Localizer.getInstance().getMessage("lblWebDeckCatalogAndMore", String.join(", ", shown), more);
        }
        return Lang.joinHomogenous(shown);
    }

    /** The cards a deck plays with: its main deck and its commanders. A Commander deck's 100 cards include both. */
    public static int played(final Deck deck) {
        return count(deck.get(DeckSection.Main)) + count(deck.get(DeckSection.Commander));
    }

    private static int count(final CardPool pool) {
        return pool == null ? 0 : pool.countAll();
    }

    /** The card-art sleeves already saved, shared with the desktop client. */
    public static List<SavedSleeveArt> savedSleeveArt() {
        final List<SavedSleeveArt> out = new ArrayList<>();
        final Map<String, Integer> library = SleeveArt.parseLibrary(
                FModel.getPreferences().getPref(FPref.UI_SLEEVE_ART_LIBRARY));
        for (final Map.Entry<String, Integer> e : library.entrySet()) {
            out.add(new SavedSleeveArt(e.getKey(), e.getValue()));
        }
        return out;
    }

    public static void forgetSleeveArt(final String imageKey) {
        final var prefs = FModel.getPreferences();
        final FPref pref = FPref.UI_SLEEVE_ART_LIBRARY;
        final Map<String, Integer> library = SleeveArt.parseLibrary(prefs.getPref(pref));
        if (library.remove(imageKey) != null) {
            prefs.setPref(pref, SleeveArt.formatLibrary(library));
            prefs.save();
        }
    }

    /** Saves a card-art sleeve with the ones desktop has saved, newest last. */
    public static void rememberSleeveArt(final String imageKey, final int offset) {
        if (imageKey == null || imageKey.isEmpty()) {
            return;
        }
        final var prefs = FModel.getPreferences();
        final FPref pref = FPref.UI_SLEEVE_ART_LIBRARY;
        final Map<String, Integer> library = SleeveArt.parseLibrary(prefs.getPref(pref));
        library.remove(imageKey);
        library.put(imageKey, SleeveArt.clampOffset(offset));
        prefs.setPref(pref, SleeveArt.formatLibrary(library));
        prefs.save();
    }

    /** What a deck's summary says at any table. */
    private record Facts(String colors, int played, int sideboard, List<String> legalIn, String formats, int averageMana) {
    }

    /** A deck that a guest stores in its browser, and the format it was built for. */
    public record OnDevice(Deck deck, GameType format) {
    }
}
