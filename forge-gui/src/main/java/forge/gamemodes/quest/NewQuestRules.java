package forge.gamemodes.quest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.game.GameFormat;
import forge.gamemodes.quest.data.DeckConstructionRules;
import forge.gamemodes.quest.data.GameFormatQuest;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.item.PaperCard;
import forge.item.PreconDeck;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.FileUtil;
import forge.util.Localizer;

/** Starting a quest from a set of choices, by the rules mobile's new-quest screen applies, for any client. */
public final class NewQuestRules {
    /** The world a Commander quest starts in, as mobile sets it when Commander is chosen. */
    public static final String COMMANDER_WORLD = "Random Commander";
    /** Sets a chosen casual format leaves out, as too powerful to start with; they can be unlocked later. */
    private static final Set<String> UNSELECTABLE = Set.of("LEA", "LEB", "MBP", "VAN", "PMOA", "ARC", "PC2");

    /** What a quest is started from: format, precon and savedDeck name the starting pool's own choice, and a null prizes takes prizes as the pool does. */
    public record Choices(String name, int difficulty, QuestMode mode, boolean commander, String world, StartingPoolType pool,
            String format, String precon, String savedDeck, StartingPoolPreferences distribution, StartingPoolType prizes,
            String prizeFormat, boolean allowUnlocks) {
    }

    private NewQuestRules() {
    }

    /** A quest's name as its file will be named, with every character a file name cannot safely hold taken out. */
    public static String cleanName(final String name) {
        return name == null ? "" : QuestUtil.cleanString(name).trim();
    }

    /** The precons a new quest may start with: those that need no wins. */
    public static List<String> startingPrecons() {
        final List<String> names = new ArrayList<>();
        for (final PreconDeck deck : QuestController.getPrecons()) {
            if (QuestController.getPreconDeals(deck).getMinWins() <= 0) {
                names.add(deck.getName());
            }
        }
        return names;
    }

    /** The formats a casual pool or prize may name, as mobile's chooser lists them, less those its unselectable sets would leave empty. */
    public static List<String> casualFormats() {
        final List<String> names = new ArrayList<>();
        for (final GameFormat format : FModel.getFormats().getOrderedList()) {
            if (!setsOf(format.getName()).isEmpty()) {
                names.add(format.getName());
            }
        }
        return names;
    }

    /** The worlds whose own sets replace the starting pool. */
    public static List<String> formatWorlds() {
        final List<String> names = new ArrayList<>();
        for (final QuestWorld world : FModel.getWorlds()) {
            if (world.getFormat() != null) {
                names.add(world.getName());
            }
        }
        return names;
    }

    /** Why a quest cannot start from these choices, or null when it can. */
    public static String problem(final Choices c) {
        final Localizer text = Localizer.getInstance();
        final String name = cleanName(c.name());
        if (name.isEmpty()) {
            return text.getMessage("lblQuestNameEmpty");
        }
        if (FileUtil.doesFileExist(ForgeConstants.QUEST_SAVE_DIR + name + ".dat")) {
            return text.getMessage("lblQuestExists");
        }
        if (worldFormat(c) == null && startingDeck(c) == null && (c.pool() == StartingPoolType.Precon || c.pool() == StartingPoolType.DraftDeck
                || c.pool() == StartingPoolType.SealedDeck || c.pool() == StartingPoolType.Cube)) {
            return text.getMessage("lbldckStartPool");
        }
        return null;
    }

    /** Makes the quest, saves it and makes it the current one. The choices must have no problem. */
    public static void start(final Choices c) {
        final GameFormat worldFormat = worldFormat(c);
        final Deck startingDeck = worldFormat == null ? startingDeck(c) : null;
        GameFormat startingFormat = worldFormat;
        if (worldFormat == null) {
            startingFormat = switch (c.pool()) {
                case Sanctioned -> FModel.getFormats().getFormat(c.format());
                case Casual, CustomFormat -> {
                    final List<String> sets = setsOf(c.format());
                    // A casual pool with no format chosen has no restriction, as mobile allows once asked
                    yield sets.isEmpty() ? null : new GameFormatQuest("Custom", sets, null);
                }
                default -> null;
            };
        }

        final GameFormat prizes;
        if (c.prizes() == null) {
            // The same as the starting pool: its format, or the sets its deck is from
            GameFormat same = startingFormat;
            if (same == null && startingDeck != null) {
                final Set<String> sets = new HashSet<>();
                for (final PaperCard card : startingDeck.getAllCardsInASinglePool().toFlatList()) {
                    sets.add(card.getEdition());
                }
                same = new GameFormat("From deck", sets, null);
            }
            prizes = same;
        } else {
            prizes = switch (c.prizes()) {
                case Sanctioned -> FModel.getFormats().getFormat(c.prizeFormat());
                case Casual, CustomFormat -> {
                    final List<String> sets = setsOf(c.prizeFormat());
                    yield sets.isEmpty() ? null : new GameFormat("Custom Prizes", sets, null);
                }
                default -> null;
            };
        }

        final String name = cleanName(c.name());
        final QuestController quest = FModel.getQuest();
        quest.newGame(name, c.difficulty(), c.mode(), prizes, c.allowUnlocks(), startingDeck, startingFormat, world(c), c.distribution(),
                c.commander() ? DeckConstructionRules.Commander : DeckConstructionRules.Default);
        quest.save();
        final QuestPreferences prefs = FModel.getQuestPreferences();
        prefs.setPref(QPref.CURRENT_QUEST, name + ".dat");
        prefs.save();
    }

    private static String world(final Choices c) {
        return c.commander() ? COMMANDER_WORLD : c.world();
    }

    /** A world's own format overrides the starting pool. */
    private static GameFormat worldFormat(final Choices c) {
        final QuestWorld world = FModel.getWorlds().get(world(c));
        return world == null ? null : world.getFormat();
    }

    private static Deck startingDeck(final Choices c) {
        if (c.pool() == null) {
            return null;
        }
        switch (c.pool()) {
            case Precon: {
                final PreconDeck precon = c.precon() == null ? null : QuestController.getPrecons().get(c.precon());
                return precon == null ? null : precon.getDeck();
            }
            case DraftDeck:
            case SealedDeck: {
                final DeckGroup group = c.savedDeck() == null ? null
                        : (c.pool() == StartingPoolType.DraftDeck ? FModel.getDecks().getDraft() : FModel.getDecks().getSealed()).get(c.savedDeck());
                return group == null ? null : group.getHumanDeck();
            }
            case Cube:
                return c.savedDeck() == null ? null : FModel.getDecks().getCubes().get(c.savedDeck());
            default:
                return null;
        }
    }

    /** A casual format's sets, less those too powerful to start with. */
    private static List<String> setsOf(final String formatName) {
        final GameFormat format = formatName == null ? null : FModel.getFormats().getFormat(formatName);
        final List<String> sets = new ArrayList<>();
        if (format != null) {
            for (final String code : format.getAllowedSetCodes()) {
                if (!UNSELECTABLE.contains(code)) {
                    sets.add(code);
                }
            }
        }
        return sets;
    }
}
