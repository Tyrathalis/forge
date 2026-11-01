package forge.web;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestEventDraft;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gamemodes.quest.io.QuestDataIO;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/** Copies quests into the test profile for a test, and removes them afterwards. */
final class QuestFixture {
    private static final List<String> made = new ArrayList<>();
    private static String currentBefore;

    private QuestFixture() {
    }

    /** The fixture save with the given name stored inside it, gzipped as Quest writes it. */
    private static byte[] packed(final String name) throws IOException {
        final String xml;
        try (InputStream in = QuestFixture.class.getResourceAsStream("/quest/Fixture_quest.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream zip = new GZIPOutputStream(bytes)) {
            // A save writes to the file named by its stored name. A copy that kept the old name would write to another file next to it
            zip.write(xml.replace("<name>Fixture quest</name>", "<name>" + name + "</name>").getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /** Copies the saved quest into the profile under a new name and reads it. It has Fantasy, Main world, 250 credits, a Plant and a Wolf at level 1, and "Forest deck" as current. */
    static QuestData install() throws IOException {
        if (currentBefore == null) {
            currentBefore = FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST);
        }
        final String name = "Quest test " + UUID.randomUUID().toString().substring(0, 8);
        final Path file = Path.of(ForgeConstants.QUEST_SAVE_DIR, name + ".dat");
        Files.createDirectories(file.getParent());
        Files.write(file, packed(name));
        made.add(name);
        return QuestDataIO.loadData(file.toFile());
    }

    static final String[] AI_NAMES = {"Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus"};

    /** A tournament in progress in the open quest, with three Magic 2010 packs and fixed seats. The player is in seat 2. The computer in seat 0 won the first round's first match, so the player's match is next. The player's deck is 40 Forests and each computer's is 40 Islands. */
    static QuestEventDraft tournament(final QuestController quest) {
        final QuestEventDraft draft = QuestEventDraft.getDraftOrNull(quest, new QuestEventDraft.QuestDraftFormat(FModel.getMagicDb().getEditions().get("M10")));
        draft.setTitle("Test tournament");
        draft.setAINames(AI_NAMES.clone());
        draft.setAIIcons(new int[] {0, 1, 2, 3, 4, 5, 6});
        final String u = QuestEventDraft.UNDETERMINED;
        draft.setStandings(new String[] {"1", "2", QuestEventDraft.HUMAN, "3", "4", "5", "6", "7", "1", u, u, u, u, u, u});
        quest.getAchievements().getDraftEvents().add(draft);
        quest.getAchievements().setCurrentDraft(draft);
        final PaperCard forest = StaticData.instance().getCommonCards().getCard("Forest");
        final PaperCard island = StaticData.instance().getCommonCards().getCard("Island");
        final DeckGroup decks = new DeckGroup(QuestEventDraft.DECK_NAME);
        final Deck human = new Deck("Tournament human");
        human.getMain().add(forest, 40);
        decks.setHumanDeck(human);
        for (int i = 0; i < AI_NAMES.length; i++) {
            final Deck ai = new Deck("AI Deck " + i);
            ai.getMain().add(island, 40);
            decks.addAiDeck(ai);
        }
        quest.getDraftDecks().add(decks);
        return draft;
    }

    /** A name that a test will create or rename a quest to. Its files are removed afterwards. */
    static String expected() {
        if (currentBefore == null) {
            currentBefore = FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST);
        }
        final String name = "Quest test " + UUID.randomUUID().toString().substring(0, 8);
        made.add(name);
        return name;
    }

    static void cleanUp() throws IOException {
        FModel.getQuest().setCurrentEvent(null);
        FModel.getQuest().load(null);
        for (final String name : made) {
            Files.deleteIfExists(Path.of(ForgeConstants.QUEST_SAVE_DIR, name + ".dat"));
            Files.deleteIfExists(Path.of(ForgeConstants.QUEST_SAVE_DIR, name + ".dat.bak"));
        }
        made.clear();
        if (currentBefore != null) {
            final QuestPreferences prefs = FModel.getQuestPreferences();
            prefs.setPref(QPref.CURRENT_QUEST, currentBefore);
            prefs.save();
            currentBefore = null;
        }
    }
}
