package forge.web;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.quest.NewQuestRules;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestDraftUtils;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.QuestMode;
import forge.gamemodes.quest.QuestSpellShop;
import forge.gamemodes.quest.QuestUtil;
import forge.gamemodes.quest.StartingPoolPreferences;
import forge.gamemodes.quest.StartingPoolType;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences.DifficultyPrefs;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.item.IPaperCard;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.item.PreconDeck;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.ItemPool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** Tests Quest's rules in the shared package, with no screen. They are tested here because forge-gui's tests have no IGuiBase or FModel setup to load the cards. */
public class QuestRulesTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() throws IOException {
        QuestUtil.setEvent(null);
        QuestFixture.cleanUp();
    }

    private static QuestEventDuel firstDuel() throws IOException {
        final QuestController quest = FModel.getQuest();
        quest.load(QuestFixture.install());
        final QuestEventDuel duel = quest.getDuelsManager().generateDuels().get(0);
        QuestUtil.setEvent(duel);
        return duel;
    }

    // Fails if a prepared duel does not put the quest's deck at the quest's life against the event's deck, under the event's name
    @Test
    public void aDuelIsPreparedWithTheQuestsDeckAndLife() throws IOException {
        final QuestEventDuel duel = firstDuel();
        final QuestController quest = FModel.getQuest();
        final PreparedMatch match = QuestUtil.prepareGame();
        assertEquals(match.players().size(), 2);
        assertSame(match.human(), match.players().get(0));
        assertEquals(match.human().getDeck().getName(), "Forest deck");
        assertEquals(match.human().getDeck().getMain().toFlatList(), QuestUtil.getCurrentDeck().getMain().toFlatList());
        assertEquals(match.human().getStartingLife(), quest.getAssets().getLife(quest.getMode()));
        assertEquals(match.players().get(1).getDeck().getName(), duel.getEventDeck().getName());
        assertEquals(match.players().get(1).getPlayer().getName(), duel.getOpponentName() == null ? duel.getTitle() : duel.getOpponentName());
        assertEquals(match.rules().getGamesPerMatch(), quest.getMatchLength());
    }

    // Fails if the plant chosen in the save is not put on the battlefield at the start
    @Test
    public void theChosenPlantStartsOnTheBattlefield() throws IOException {
        firstDuel();
        final QuestController quest = FModel.getQuest();
        quest.selectPet(0, "Plant");
        final IPaperCard plant = quest.getPetsStorage().getPet("Plant").getPetCard(quest.getAssets());
        final RegisteredPlayer human = QuestUtil.prepareGame().human();
        final List<String> names = new ArrayList<>();
        human.getCardsOnBattlefield().forEach(c -> names.add(c.getName()));
        assertTrue(names.contains(plant.getName()), "the battlefield holds " + names);
    }

    private static ItemPool<InventoryItem> poolOf(final InventoryItem item, final int count) {
        final ItemPool<InventoryItem> pool = new ItemPool<>(InventoryItem.class);
        pool.add(item, count);
        return pool;
    }

    // Fails if a card's extras are not the copies above a playset, or a basic land's extras are not the copies above the any-number setting
    @Test
    public void extrasAreWhatIsAboveAPlayset() throws IOException {
        FModel.getQuest().load(QuestFixture.install());
        final PaperCard shock = StaticData.instance().getCommonCards().getCard("Shock");
        final PaperCard forest = StaticData.instance().getCommonCards().getCard("Forest");
        final ItemPool<InventoryItem> owned = poolOf(shock, 7);
        owned.add(forest, 60);
        final ItemPool<InventoryItem> extras = QuestSpellShop.extras(owned);
        assertEquals(extras.count(shock), 7 - FModel.getQuestPreferences().getPrefInt(QPref.PLAYSET_SIZE));
        assertEquals(extras.count(forest), Math.max(0, 60 - FModel.getQuestPreferences().getPrefInt(QPref.PLAYSET_ANY_NUMBER_SIZE)));
    }

    private static NewQuestRules.Choices choices(final String name, final StartingPoolType pool, final String format, final String precon) {
        return new NewQuestRules.Choices(name, 0, QuestMode.Fantasy, false, "Main world", pool, format, precon, null,
                new StartingPoolPreferences(StartingPoolPreferences.PoolType.BALANCED, new ArrayList<>(), false, false, false, 0), null, null, true);
    }

    // Fails if a Sanctioned-format quest does not start with a pool from that format's sets and the difficulty's credits
    @Test
    public void aSanctionedQuestStartsFromItsFormat() {
        final String name = QuestFixture.expected();
        final GameFormat format = FModel.getFormats().getSanctionedList().iterator().next();
        NewQuestRules.start(choices(name, StartingPoolType.Sanctioned, format.getName(), null));
        final QuestController quest = FModel.getQuest();
        assertEquals(quest.getName(), name);
        assertEquals(quest.getAssets().getCredits(), FModel.getQuestPreferences().getPrefInt(DifficultyPrefs.STARTING_CREDITS, 0));
        assertTrue(quest.getCards().getCardpool().countAll() > 0);
        for (final PaperCard card : quest.getCards().getCardpool().toFlatList()) {
            if (!card.getRules().getType().isBasicLand()) {
                assertTrue(format.isSetLegal(card.getEdition()), card + " is not from " + format.getName());
            }
        }
        assertTrue(new File(ForgeConstants.QUEST_SAVE_DIR, name + ".dat").isFile());
        assertEquals(FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST), name + ".dat");
    }

    // Fails if a precon quest does not start with that deck and nothing else
    @Test
    public void aPreconQuestStartsWithItsDeck() {
        final String name = QuestFixture.expected();
        String precon = null;
        for (final PreconDeck deck : QuestController.getPrecons()) {
            if (precon == null && QuestController.getPreconDeals(deck).getMinWins() <= 0) {
                precon = deck.getName();
            }
        }
        assertNotNull(precon, "no precon is offered at the start");
        NewQuestRules.start(choices(name, StartingPoolType.Precon, null, precon));
        final Deck deck = QuestController.getPrecons().get(precon).getDeck();
        final QuestController quest = FModel.getQuest();
        assertEquals(quest.getMyDecks().size(), 1);
        for (final PaperCard card : quest.getCards().getCardpool().toFlatList()) {
            if (!card.getRules().getType().isBasicLand()) {
                assertTrue(deck.getMain().contains(card), card + " is not in " + precon);
            }
        }
        deck.getMain().forEach(e -> assertTrue(quest.getCards().getCardpool().count(e.getKey()) >= e.getValue(), e.getKey() + " is missing"));
    }

    // Fails if another quest's name is accepted, or the path is not removed from a name that contains one
    @Test
    public void aQuestsNameIsCleanAndFree() throws IOException {
        final QuestData data = QuestFixture.install();
        assertNotNull(NewQuestRules.problem(choices(data.getName(), StartingPoolType.Complete, null, null)));
        assertNotNull(NewQuestRules.problem(choices("../", StartingPoolType.Complete, null, null)));
        final String name = QuestFixture.expected();
        assertNull(NewQuestRules.problem(choices("../" + name + "/", StartingPoolType.Complete, null, null)));
        assertEquals(NewQuestRules.cleanName("../" + name + "/"), name);
        assertFalse(new File(ForgeConstants.QUEST_SAVE_DIR, name + ".dat").exists());
    }

    // Fails if the prepared match is not the player against the bracket's next opponent with the draft's deck
    @Test
    public void aTournamentsNextMatchIsThePlayers() throws IOException {
        final QuestController quest = FModel.getQuest();
        quest.load(QuestFixture.install());
        QuestFixture.tournament(quest);
        final PreparedMatch match = QuestDraftUtils.prepareNextMatch();
        assertNotNull(match, "the player's match was not prepared");
        assertEquals(match.rules().getGameType(), GameType.QuestDraft);
        assertEquals(match.players().size(), 2);
        assertEquals(match.human().getDeck().getName(), "Tournament human");
        // Seat 3 has computer 3, which has the third name
        final RegisteredPlayer opponent = match.players().get(0) == match.human() ? match.players().get(1) : match.players().get(0);
        assertEquals(opponent.getPlayer().getName(), QuestFixture.AI_NAMES[2]);
        assertEquals(opponent.getDeck().getName(), "AI Deck 2");
    }
}
