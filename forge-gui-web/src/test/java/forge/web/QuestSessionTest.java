package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestEventDraft;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.QuestUtil;
import forge.gamemodes.quest.data.GameFormatQuest;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gamemodes.quest.io.QuestDataIO;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.ImageUtil;
import forge.util.Localizer;
import forge.web.campaign.QuestGame;
import forge.web.protocol.JsonCodec;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Tests a quest driven from a browser, with its duels played over netplay. */
public class QuestSessionTest extends SessionsTest {
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    @Override
    void afterDisconnecting() {
        try {
            QuestFixture.cleanUp();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The amount the bar shows for the balance with this icon. */
    private static int balance(final JsonObject bar, final String icon) {
        for (final JsonElement b : bar.getAsJsonArray("balances")) {
            if (icon.equals(b.getAsJsonObject().get("icon").getAsString())) {
                return b.getAsJsonObject().get("amount").getAsInt();
            }
        }
        throw new AssertionError("The bar has no balance of " + icon);
    }

    /** Connects the host and opens the list of saved quests, whatever state the last test left the session in. */
    private TestBrowser onTheShelf() throws InterruptedException {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        host.awaitMatching("hello", h -> h.get("host").getAsBoolean(), "the host's seat was not given");
        sessions.onMessage(host, message("setName", "name", "Host"));
        send(host, message("campaignOpen", "mode", "quest", "resume", false));
        host.awaitMatching("hello", h -> "quest".equals(str(h, "campaign")) && str(h, "campaignSave") == null, "the shelf did not open");
        host.awaitMatching("questSaves", s -> true, "the quests were not listed");
        return host;
    }

    private TestBrowser hostInQuest(final QuestData data) throws InterruptedException {
        final TestBrowser host = onTheShelf();
        send(host, message("campaignLoad", "name", data.getName()));
        host.awaitMatching("hello", h -> data.getName().equals(str(h, "campaignSave")), "the quest did not open");
        return host;
    }

    private TestBrowser editor;

    // The session lives longer than a test. Without this, the next test's browser would see an open editor or a removed quest
    @AfterMethod(alwaysRun = true)
    public void leaveTheQuest() throws InterruptedException {
        if (editor != null) {
            sessions.onMessage(editor, JsonCodec.message("editorClose"));
            editor = null;
        }
        TestBrowser host = null;
        for (final TestBrowser browser : browsers) {
            if (browser.hello != null && browser.hello.get("host").getAsBoolean()) {
                host = browser;
            }
        }
        if (host == null) {
            return;
        }
        if ("playing".equals(host.hello.get("screen").getAsString())) {
            send(host, JsonCodec.message("leave"));
            if (host.awaitMatching("hello", h -> !"playing".equals(h.get("screen").getAsString())) == null) {
                return;
            }
        }
        if (host.hello.get("drafting").getAsBoolean()) {
            send(host, JsonCodec.message("draftDiscard"));
            host.awaitMatching("hello", h -> !h.get("drafting").getAsBoolean());
        }
        if (str(host.hello, "campaignSave") != null) {
            send(host, JsonCodec.message("campaignLeave"));
            // The shelf reads every save when it opens. The cleanup must not remove a save during that read
            if (host.awaitMatching("questSaves", saves -> true) == null) {
                return;
            }
        }
        if ("quest".equals(str(host.hello, "campaign"))) {
            send(host, JsonCodec.message("campaignLeave"));
            host.awaitMatching("hello", h -> !"quest".equals(str(h, "campaign")));
        }
    }

    // Fails if a quest cannot be opened from its shelf, or opening it does not make it the current quest
    @Test(timeOut = 120_000)
    public void aQuestOpensFromItsShelf() throws Exception {
        final QuestData data = QuestFixture.install();
        final TestBrowser host = hostInQuest(data);
        Assert.assertEquals(FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST), data.getName() + ".dat");
        Assert.assertEquals(FModel.getQuest().getName(), data.getName());
        final JsonObject bar = host.awaitNewest("campaignBar", "the bar was not sent");
        Assert.assertEquals(balance(bar, "ICO_QUEST_COINSTACK"), 250);
        Assert.assertEquals(str(bar, "name"), data.getName());
    }

    /** The save on disk, read the same way desktop reads it. */
    private static QuestData saved(final QuestData data) throws IOException {
        return QuestDataIO.loadData(QuestGame.find(data.getName()));
    }

    // Fails if the page does not list the duels manager's duels in its order, with the random opponent's title hidden
    @Test(timeOut = 120_000)
    public void theDuelsAreTheManagers() throws Exception {
        final QuestData data = QuestFixture.install();
        final TestBrowser host = hostInQuest(data);
        final JsonObject page = host.awaitNewest("questDuels", "the duels were not sent");
        final List<QuestEventDuel> expected = FModel.getQuest().getDuelsManager().generateDuels();
        final JsonArray rows = page.getAsJsonArray("duels");
        Assert.assertEquals(rows.size(), expected.size());
        for (int i = 0; i < rows.size(); i++) {
            final JsonObject row = rows.get(i).getAsJsonObject();
            final QuestEventDuel duel = expected.get(i);
            Assert.assertEquals(row.get("index").getAsInt(), i);
            if (duel.showDifficulty()) {
                Assert.assertFalse(row.get("random").getAsBoolean());
                Assert.assertEquals(row.get("title").getAsString(), duel.getTitle());
                Assert.assertEquals(row.get("difficulty").getAsInt(), duel.getDifficulty().ordinal() + 1);
                Assert.assertNotNull(str(row, "face"), duel.getTitle() + " has no face");
            } else {
                Assert.assertTrue(row.get("random").getAsBoolean());
                Assert.assertEquals(row.get("difficulty").getAsInt(), 0);
                Assert.assertNull(str(row, "face"), "the random opponent's deck shows");
                Assert.assertNotEquals(row.get("title").getAsString(), duel.getOpponentName());
            }
        }
        Assert.assertEquals(page.get("deck").getAsString(), "Forest deck");
        Assert.assertEquals(page.get("matchLength").getAsInt(), 3);
    }

    // Fails if choosing no plant does not empty its slot in the save, choosing the Wolf does not fill the pet slot, or an unowned pet is saved
    @Test(timeOut = 120_000)
    public void petChoicesAreSaved() throws Exception {
        final QuestData data = QuestFixture.install();
        final TestBrowser host = hostInQuest(data);
        host.awaitNewest("questDuels", "the duels were not sent");
        send(host, message("questPet", "slot", 0, "name", "Plant"));
        host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertEquals(saved(data).getPetSlots().get(0), "Plant");
        send(host, message("questPet", "slot", 0));
        host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertNull(saved(data).getPetSlots().get(0));
        send(host, message("questPet", "slot", 1, "name", "Wolf"));
        final JsonObject page = host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertEquals(saved(data).getPetSlots().get(1), "Wolf");
        Assert.assertEquals(page.getAsJsonArray("pets").get(1).getAsJsonObject().get("chosen").getAsString(), "Wolf");
        // A pet the quest does not own cannot be chosen
        send(host, message("questPet", "slot", 1, "name", "Bird"));
        host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertEquals(saved(data).getPetSlots().get(1), "Wolf");
    }

    private boolean devModeBefore;

    @BeforeClass
    public void allowTheGameToBeWon() {
        WebTestSupport.initModel();
        devModeBefore = FModel.getPreferences().getPrefBoolean(FPref.DEV_MODE_ENABLED);
        FModel.getPreferences().setPref(FPref.DEV_MODE_ENABLED, true);
    }

    @AfterClass(alwaysRun = true)
    public void restoreDevMode() {
        FModel.getPreferences().setPref(FPref.DEV_MODE_ENABLED, devModeBefore);
    }

    /** Starts the quest's first duel for the host and waits for the host's first priority. */
    private TestBrowser inDuel(final QuestData data) throws InterruptedException {
        final TestBrowser host = hostInQuest(data);
        host.awaitNewest("questDuels", "the duels were not sent");
        send(host, message("questDuel", "index", 0));
        host.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the duel did not start");
        awaitPriority(host);
        return host;
    }

    private void answer(final TestBrowser host, final JsonObject question, final int option) {
        final JsonObject reply = message("hostChoice", "id", question.get("id").getAsInt());
        final JsonArray value = new JsonArray();
        value.add(option);
        reply.add("value", value);
        sessions.onMessage(host, reply);
    }

    /** Gives the first option to each question the reward script asks, until the reward arrives. */
    private JsonObject untilRewarded(final TestBrowser host) throws InterruptedException {
        final Set<Integer> asked = new HashSet<>();
        final long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            for (final JsonObject m : host.got) {
                final String type = m.get("t").getAsString();
                if ("reward".equals(type)) {
                    return m;
                }
                if ("hostChoice".equals(type) && asked.add(m.get("id").getAsInt())) {
                    answer(host, m, 0);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the reward was not sent: " + host.got.stream().map(m -> m.get("t").getAsString()).distinct().toList());
    }

    private static JsonObject step(final JsonObject reward, final String kind, final String title) {
        for (final JsonElement e : reward.getAsJsonArray("steps")) {
            final JsonObject s = e.getAsJsonObject();
            if (kind.equals(s.get("kind").getAsString()) && str(s, "title") != null && str(s, "title").contains(title)) {
                return s;
            }
        }
        throw new AssertionError("no " + kind + " step titled " + title + " in " + reward);
    }

    // Fails if a won duel is not recorded exactly once, or its credits are not in the save when the reward is shown
    @Test(timeOut = 240_000)
    public void aWonDuelIsRecordedOnce() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = QuestFixture.install();
        data.setMatchLength(1);
        data.saveData();
        final TestBrowser host = inDuel(data);
        computerLoses(host);
        final JsonObject result = host.awaitMatching("campaignResult", r -> true, "no result was sent");
        Assert.assertTrue(result.get("won").getAsBoolean());
        Assert.assertEquals(buttons(result), List.of("leave"));

        // A reload on the result screen shows the same result and records nothing
        final TestBrowser reloaded = connect("host");
        Assert.assertEquals(reloaded.awaitMatching("campaignResult", r -> true, "a reload lost the result"), result);
        sessions.onMessage(reloaded, JsonCodec.message("leave"));
        final JsonObject reward = untilRewarded(reloaded);
        step(reward, "MESSAGE", Localizer.getInstance().getMessage("lblGameplayResults"));
        final QuestData after = saved(data);
        Assert.assertEquals(after.getAchievements().getWin(), 1);
        Assert.assertEquals(after.getAchievements().getLost(), 0);
        Assert.assertTrue(after.getAssets().getCredits() > 250, "no credits were won: " + after.getAssets().getCredits());

        final TestBrowser again = connect("host");
        Assert.assertEquals(again.awaitMatching("reward", r -> true, "a reload lost the reward"), reward);
        Assert.assertEquals(saved(data).getAchievements().getWin(), 1);

        send(again, JsonCodec.message("rewardClaim"));
        final TestBrowser third = connect("host");
        third.awaitMatching("questDuels", s -> true, "the page was not sent");
        Assert.assertFalse(third.got.stream().anyMatch(m -> "reward".equals(m.get("t").getAsString())), "a claimed reward was sent again");
    }

    // Fails if a duel quit before its match is over is not recorded as a loss with the penalty
    @Test(timeOut = 240_000)
    public void quittingADuelEarlyIsALossWithThePenalty() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = QuestFixture.install();
        final TestBrowser host = inDuel(data);
        sessions.onMessage(host, JsonCodec.message("concede"));
        Assert.assertEquals(buttons(host.awaitMatching("campaignResult", r -> true, "no result was sent")), List.of("nextGame", "quit"));
        sessions.onMessage(host, message("nextGame", "decision", "QUIT"));
        send(host, JsonCodec.message("leave"));
        host.awaitMatching("questDuels", s -> true, "leaving did not return to the duels");
        final QuestData after = saved(data);
        Assert.assertEquals(after.getAchievements().getLost(), 1);
        Assert.assertEquals(after.getAchievements().getWin(), 0);
        Assert.assertEquals(after.getAssets().getCredits(), 250 - FModel.getQuestPreferences().getPrefInt(QPref.PENALTY_LOSS));
    }

    /** The named deck as stored in the quest's save file, or null. */
    private static Deck savedDeck(final QuestData data, final String name) throws IOException {
        return saved(data).getAssets().getDeckStorage().get(name);
    }

    // Fails if renaming the current deck leaves the quest pointing at the old name
    @Test(timeOut = 120_000)
    public void renamingTheCurrentDeckKeepsItCurrent() throws Exception {
        final QuestData data = QuestFixture.install();
        final TestBrowser host = hostInQuest(data);
        send(host, message("questDeckRename", "deck", "Forest deck", "to", "Green deck"));
        host.awaitNewest("questDecks", d -> d.toString().contains("Green deck"), "the decks were not sent again");
        Assert.assertNull(savedDeck(data, "Forest deck"));
        Assert.assertEquals(savedDeck(data, "Green deck").getMain().countAll(), 40);
        Assert.assertEquals(saved(data).getAssets().getDeckStorage().get("Green deck").getName(), "Green deck");
        Assert.assertEquals(FModel.getQuest().getCurrentDeck(), "Green deck");
        Assert.assertEquals(QuestUtil.getCurrentDeck().getName(), "Green deck");
    }

    // Fails if Quest's own reader does not read back a web-edited deck with the same cards, or the editor adds a card the quest does not own
    @Test(timeOut = 120_000)
    public void anEditedDeckReadsBackInQuest() throws Exception {
        final QuestData data = QuestFixture.install();
        final TestBrowser host = hostInQuest(data);
        final PaperCard owned = data.getAssets().getCardPool().toFlatList().stream().filter(c -> !c.getRules().getType().isLand()).findFirst().orElseThrow();
        editor = host;
        send(host, message("questDeckEdit", "deck", "Forest deck"));
        host.awaitMatching("editor", e -> e.has("state"), "the editor did not open");
        send(host, message("editorEdit", "op", "add", "name", owned.getName(), "to", "Sideboard", "count", 1));
        host.awaitMatching("editor", e -> e.has("state"), "the editor did not answer");
        send(host, message("editorEdit", "op", "add", "name", "Black Lotus", "to", "Main", "count", 1));
        host.awaitMatching("notice", e -> true, "a card not owned was not refused");
        final Deck back = savedDeck(data, "Forest deck");
        Assert.assertEquals(back.getMain().countAll(), 40);
        Assert.assertEquals(back.get(DeckSection.Sideboard).count(owned), 1);
        Assert.assertEquals(back.getMain().countByName("Black Lotus"), 0);
    }

    /** Requests a page of one of the quest's lists, as the trade page does. */
    private JsonObject listed(final TestBrowser host, final String source) throws InterruptedException {
        send(host, message("catalogue", "request", 1, "text", "", "colours", "", "type", "any", "filters", "", "sort", "name",
                "offset", 0, "showAll", true, "source", source));
        return host.awaitMatching("catalogue", c -> source.equals(str(c, "source")), "the " + source + " list was not sent");
    }

    private static JsonObject row(final JsonObject page, final Predicate<JsonObject> wanted) {
        for (final JsonElement e : page.getAsJsonArray("rows")) {
            if (wanted.test(e.getAsJsonObject())) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no such row in " + page);
    }

    private void trade(final TestBrowser host, final String source, final String key, final int count) {
        final JsonObject pick = new JsonObject();
        pick.addProperty("key", key);
        pick.addProperty("count", count);
        final JsonArray picks = new JsonArray();
        picks.add(pick);
        final JsonObject msg = message("trade", "source", source);
        msg.add("picks", picks);
        send(host, msg);
    }

    private static PaperCard printing(final JsonObject row) {
        return ImageUtil.getPaperCardFromImageKey(row.get("image").getAsString());
    }

    // Fails if a bought card is not removed from the stock once and added to the pool once
    @Test(timeOut = 120_000)
    public void aBoughtCardMovesOnceFromStockToPool() throws Exception {
        final QuestData data = QuestFixture.install();
        final TestBrowser host = hostInQuest(data);
        FModel.getQuest().getAssets().setCredits(100_000);
        final JsonObject card = row(listed(host, "shop"), r -> true);
        final PaperCard bought = printing(card);
        final int stock = FModel.getQuest().getCards().getShopList().count(bought);
        final int owned = FModel.getQuest().getCards().getCardpool().count(bought);
        Assert.assertEquals(card.get("count").getAsInt(), stock);
        trade(host, "shop", card.get("image").getAsString(), 1);
        host.awaitMatching("trading", t -> true, "the lists were not sent again");
        Assert.assertEquals(FModel.getQuest().getCards().getShopList().count(bought), stock - 1);
        Assert.assertEquals(saved(data).getAssets().getCardPool().count(bought), owned + 1);
        Assert.assertEquals(saved(data).getAssets().getCredits(), 100_000 - card.get("value").getAsInt());
    }

    // Fails if selling one of two copies leaves the lists' sizes unchanged. A size change is what makes the page reload its lists
    @Test(timeOut = 120_000)
    public void aPartialSaleChangesTheListsSizes() throws Exception {
        final QuestData data = QuestFixture.install();
        final TestBrowser host = hostInQuest(data);
        final JsonObject card = row(listed(host, "inventory"), r -> !"Forest".equals(r.get("name").getAsString()));
        FModel.getQuest().getCards().getCardpool().add(printing(card), 1);
        send(host, JsonCodec.message("trading"));
        final JsonObject before = host.awaitMatching("trading", t -> true, "the lists were not sent");
        trade(host, "inventory", card.get("key").getAsString(), 1);
        final JsonObject after = host.awaitMatching("trading", t -> true, "the lists were not sent again");
        Assert.assertNotEquals(after.get("lists"), before.get("lists"));
        Assert.assertEquals(FModel.getQuest().getCards().getCardpool().count(printing(card)), 1);
    }

    // Fails if a foil and a plain copy of one printing share a key, so that selling one sells the other or pays the other's price
    @Test(timeOut = 120_000)
    public void aFoilTradesApartFromItsPlainCopy() throws Exception {
        final QuestData data = QuestFixture.install();
        final TestBrowser host = hostInQuest(data);
        final PaperCard plain = printing(row(listed(host, "inventory"), r -> !"Forest".equals(r.get("name").getAsString())));
        final PaperCard foil = plain.getFoiled();
        FModel.getQuest().getCards().getCardpool().add(foil, 1);
        final JsonObject page = listed(host, "inventory");
        final JsonObject foilRow = row(page, r -> r.get("name").getAsString().equals(plain.getName()) && r.get("key").getAsString().endsWith("foil"));
        Assert.assertNotEquals(foilRow.get("key"), row(page, r -> r.get("name").getAsString().equals(plain.getName())
                && !r.get("key").getAsString().endsWith("foil")).get("key"));
        final long credits = FModel.getQuest().getAssets().getCredits();
        trade(host, "inventory", foilRow.get("key").getAsString(), 1);
        host.awaitMatching("trading", t -> true, "the lists were not sent again");
        Assert.assertEquals(FModel.getQuest().getCards().getCardpool().count(foil), 0);
        Assert.assertEquals(FModel.getQuest().getCards().getCardpool().count(plain), 1);
        Assert.assertEquals(FModel.getQuest().getAssets().getCredits(), credits + foilRow.get("value").getAsInt());
    }

    // Fails if a quest made from the form is not opened and made current, or the form's options are not sent or miss a world's format or a casual format
    @Test(timeOut = 120_000)
    public void aQuestMadeFromTheFormOpens() throws Exception {
        final TestBrowser host = onTheShelf();
        send(host, JsonCodec.message("questOptions"));
        final JsonObject options = host.awaitMatching("questOptions", o -> true, "the form's options were not sent");
        Assert.assertTrue(options.getAsJsonArray("worlds").contains(new JsonPrimitive("Main world")));
        Assert.assertEquals(options.getAsJsonArray("difficulties").size(), 4);
        // A world with its own sets replaces the starting pool. The casual formats are mobile's full list, minus formats made only of unselectable sets
        Assert.assertTrue(options.getAsJsonArray("formatWorlds").contains(new JsonPrimitive("Ravnica")));
        Assert.assertFalse(options.getAsJsonArray("formatWorlds").contains(new JsonPrimitive("Main world")));
        Assert.assertTrue(options.getAsJsonArray("casual").contains(new JsonPrimitive("Premodern")));
        Assert.assertFalse(options.getAsJsonArray("casual").contains(new JsonPrimitive("Pre-format (LEB)")));
        final String name = QuestFixture.expected();
        send(host, message("questCreate", "name", name, "difficulty", 1, "fantasy", true, "commander", false, "world", "Main world",
                "pool", "Complete", "poolType", "BALANCED", "colors", "G", "artifacts", false, "completeSet", false, "duplicates", false,
                "boosters", 0, "allowUnlocks", true));
        host.awaitMatching("hello", h -> name.equals(str(h, "campaignSave")), "the new quest was not opened");
        Assert.assertEquals(FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST), name + ".dat");
        Assert.assertEquals(FModel.getQuest().getName(), name);
        Assert.assertEquals(FModel.getQuest().getAchievements().getDifficulty(), 1);
    }

    // Fails if deleting a quest leaves its file or its backup, or leaves it set as the current quest
    @Test(timeOut = 120_000)
    public void deletingAQuestRemovesBothFiles() throws Exception {
        final QuestData data = QuestFixture.install();
        // A second save copies the first to the backup
        data.saveData();
        final File backup = new File(ForgeConstants.QUEST_SAVE_DIR, data.getName() + ".dat.bak");
        Assert.assertTrue(backup.isFile());
        final TestBrowser host = onTheShelf();
        FModel.getQuestPreferences().setPref(QPref.CURRENT_QUEST, data.getName() + ".dat");
        send(host, message("campaignDelete", "name", data.getName()));
        host.awaitMatching("questSaves", q -> !q.toString().contains(data.getName()), "the shelf was not sent without the quest");
        Assert.assertNull(QuestGame.find(data.getName()));
        Assert.assertFalse(backup.exists());
        Assert.assertNotEquals(FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST), data.getName() + ".dat");
    }

    /** The fixture with enough wins to be offered challenges, and one game per match. */
    private static QuestData withChallenges() throws IOException {
        final QuestData data = QuestFixture.install();
        for (int i = 0; i < 40; i++) {
            data.getAchievements().addWin();
        }
        data.setMatchLength(1);
        data.saveData();
        return data;
    }

    // Fails if a won challenge is not removed from the list, or its bounty is not paid
    @Test(timeOut = 240_000)
    public void aWonChallengeIsPaidAndGone() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = withChallenges();
        final TestBrowser host = hostInQuest(data);
        final JsonObject row = host.awaitNewest("questChallenges", "the challenges were not sent").getAsJsonArray("challenges").get(0).getAsJsonObject();
        final String id = row.get("id").getAsString();
        final long credits = FModel.getQuest().getAssets().getCredits();
        send(host, message("questChallenge", "id", id));
        host.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the challenge did not start");
        awaitPriority(host);
        computerLoses(host);
        host.awaitMatching("campaignResult", r -> true, "no result was sent");
        sessions.onMessage(host, JsonCodec.message("leave"));
        untilRewarded(host);
        final QuestData after = saved(data);
        Assert.assertFalse(after.getAchievements().getCurrentChallenges().contains(id), "the won challenge is still offered");
        Assert.assertTrue(after.getAssets().getCredits() >= credits + row.get("credits").getAsInt(),
                "the bounty was not paid: " + credits + " then " + after.getAssets().getCredits());
    }

    private JsonObject stall(final TestBrowser host, final String name) throws InterruptedException {
        send(host, message("questStall", "name", name));
        return host.awaitMatching("questBazaar", b -> true, "the bazaar was not sent");
    }

    private static JsonObject item(final JsonObject bazaar, final String name) {
        for (final JsonElement e : bazaar.getAsJsonArray("items")) {
            if (name.equals(e.getAsJsonObject().get("name").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    /** The fixture with QuestFixture's tournament added, changed as the test needs, and saved. */
    private static QuestData withTournament(final Consumer<QuestEventDraft> change) throws IOException {
        final QuestData data = QuestFixture.install();
        final QuestController quest = FModel.getQuest();
        quest.load(data);
        change.accept(QuestFixture.tournament(quest));
        quest.save();
        return data;
    }

    private static String[] standings(final String... seats) {
        final String[] all = new String[15];
        Arrays.fill(all, QuestEventDraft.UNDETERMINED);
        for (int i = 0; i < seats.length; i++) {
            all[i] = "u".equals(seats[i]) ? QuestEventDraft.UNDETERMINED : "h".equals(seats[i]) ? QuestEventDraft.HUMAN : seats[i];
        }
        return all;
    }

    private static QuestEventDraft draftOf(final QuestData data) throws IOException {
        return saved(data).getAchievements().getDraftEvents().stream().filter(d -> "Test tournament".equals(d.getTitle())).findFirst().orElse(null);
    }

    /** Starts the player's tournament match from the tournaments page and waits for priority. */
    private TestBrowser inTournamentMatch(final QuestData data) throws InterruptedException {
        final TestBrowser host = hostInQuest(data);
        host.awaitNewest("questTournaments", "the tournaments were not sent");
        send(host, JsonCodec.message("questTournamentNext"));
        host.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the tournament match did not start");
        awaitPriority(host);
        return host;
    }

    // Fails if entering a tournament does not charge its fee and open its draft, or its tile lacks a picture for each pack
    @Test(timeOut = 180_000)
    public void enteringATournamentTakesItsFeeAndOpensItsDraft() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = QuestFixture.install();
        data.getAssets().setCredits(100_000);
        data.saveData();
        final TestBrowser host = hostInQuest(data);
        final JsonObject page = host.awaitNewest("questTournaments", "the tournaments were not sent");
        final JsonArray offered = page.getAsJsonArray("offered");
        Assert.assertTrue(offered.size() > 0, "no tournament is offered: " + page);
        final JsonObject row = offered.get(0).getAsJsonObject();
        Assert.assertTrue(row.get("affordable").getAsBoolean());
        final JsonArray pictures = row.getAsJsonArray("packImages");
        Assert.assertEquals(pictures.size(), row.getAsJsonArray("packs").size());
        Assert.assertTrue(pictures.get(0).getAsString().startsWith("b:"), "not a booster's picture: " + pictures);
        send(host, message("questEnter", "title", row.get("title").getAsString()));
        host.awaitMatching("hello", h -> h.get("drafting").getAsBoolean(), "the draft did not open");
        final JsonObject first = host.awaitMatching("draft", d -> !d.getAsJsonArray("cards").isEmpty(), "no pack was dealt");
        Assert.assertEquals(first.get("pack").getAsInt(), 1);
        final QuestData after = saved(data);
        Assert.assertEquals(after.getAssets().getCredits(), 100_000 - row.get("fee").getAsInt());
        Assert.assertEquals(after.getAchievements().getCurrentDraft().getTitle(), row.get("title").getAsString());
    }

    // Fails if the computer's matches are not decided before the player's match, which must be the bracket's next match
    @Test(timeOut = 240_000)
    public void theComputersMatchesComeBeforeThePlayers() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = withTournament(d -> d.setStandings(standings("1", "2", "3", "4", "h", "5", "6", "7")));
        final TestBrowser host = hostInQuest(data);
        final JsonObject before = host.awaitNewest("questTournaments", "the tournaments were not sent");
        Assert.assertFalse(before.getAsJsonObject("bracket").get("started").getAsBoolean());
        send(host, JsonCodec.message("questTournamentStart"));
        host.awaitMatching("questTournaments", p -> p.getAsJsonObject("bracket").get("started").getAsBoolean(), "the tournament did not start");
        send(host, JsonCodec.message("questTournamentNext"));
        host.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the player's match did not start");
        final String[] standings = draftOf(data).getStandings();
        Assert.assertNotEquals(standings[8], QuestEventDraft.UNDETERMINED, "the first computer match was not decided");
        Assert.assertNotEquals(standings[9], QuestEventDraft.UNDETERMINED, "the second computer match was not decided");
        Assert.assertEquals(standings[10], QuestEventDraft.UNDETERMINED, "the player's match was decided without a game");
    }

    // Fails if a won tournament match does not advance the player in the bracket
    @Test(timeOut = 300_000)
    public void aWonTournamentMatchAdvancesThePlayer() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = withTournament(d -> d.setStarted(true));
        final TestBrowser host = inTournamentMatch(data);
        computerLoses(host);
        Assert.assertEquals(buttons(host.awaitMatching("campaignResult", r -> true, "no result was sent")), List.of("nextGame", "forfeit"));
        send(host, message("nextGame", "decision", "CONTINUE"));
        awaitPriority(host);
        computerLoses(host);
        Assert.assertEquals(buttons(host.awaitMatching("campaignResult", r -> true, "no second result was sent")), List.of("leave"));
        send(host, JsonCodec.message("leave"));
        final JsonObject page = host.awaitMatching("questTournaments", p -> true, "leaving did not return to the tournaments");
        Assert.assertEquals(draftOf(data).getStandings()[9], QuestEventDraft.HUMAN);
        final JsonArray second = page.getAsJsonObject("bracket").getAsJsonArray("rounds").get(1).getAsJsonArray();
        Assert.assertTrue(second.get(1).getAsJsonObject().get("you").getAsBoolean(), "the bracket does not show the player in the second round: " + page);
    }

    // Fails if a finished tournament's prizes are not saved and sent to reveal, the tournament stays open, or the rare choice has no pictures
    @Test(timeOut = 240_000)
    public void aFinishedTournamentsPrizesAreSavedAndShown() throws Exception {
        final QuestData data = withTournament(d -> {
            d.setStandings(standings("1", "2", "h", "3", "4", "5", "6", "7", "1", "h", "4", "6", "h", "4", "h"));
            d.setStarted(true);
        });
        final long credits = data.getAssets().getCredits();
        final int cards = data.getAssets().getCardPool().countAll();
        final TestBrowser host = hostInQuest(data);
        host.awaitNewest("questTournaments", "the tournaments were not sent");
        send(host, JsonCodec.message("questTournamentLeave"));
        // Take the first rare offered, and do not copy the draft to the regular drafts
        JsonObject reward = null;
        final Set<Integer> asked = new HashSet<>();
        final long end = System.currentTimeMillis() + 60_000;
        while (reward == null && System.currentTimeMillis() < end) {
            for (final JsonObject m : host.got) {
                if ("reward".equals(m.get("t").getAsString())) {
                    reward = m;
                } else if ("hostChoice".equals(m.get("t").getAsString()) && asked.add(m.get("id").getAsInt())) {
                    if (!"confirm".equals(m.get("kind").getAsString())) {
                        Assert.assertEquals(str(m, "pictured"), "card");
                        Assert.assertEquals(m.getAsJsonArray("images").size(), m.getAsJsonArray("options").size());
                    }
                    answer(host, m, "confirm".equals(m.get("kind").getAsString()) ? 1 : 0);
                }
            }
            Thread.sleep(50);
        }
        Assert.assertNotNull(reward, "the prizes were not sent");
        step(reward, "CARDS", Localizer.getInstance().getMessage("lblTournamentReward"));
        final QuestData after = saved(data);
        Assert.assertTrue(after.getAssets().getCredits() > credits, "no credits were won: " + credits + " then " + after.getAssets().getCredits());
        Assert.assertTrue(after.getAssets().getCardPool().countAll() > cards, "no cards were won");
        Assert.assertEquals(after.getAchievements().getDraftTokens(), 1, "first place's token was not given");
        Assert.assertEquals(after.getAchievements().getWinsForPlace(1), 1);
        Assert.assertNull(after.getAchievements().getCurrentDraft(), "the tournament is still open");
    }

    // Fails if asking for the next match hangs once the player is out, starts a match, or still offers one
    @Test(timeOut = 120_000)
    public void nothingIsDecidedOnceThePlayerIsOut() throws Exception {
        final QuestData data = withTournament(d -> {
            d.setStandings(standings("1", "2", "h", "3", "4", "5", "6", "7", "1", "3"));
            d.setStarted(true);
        });
        final TestBrowser host = hostInQuest(data);
        host.awaitNewest("questTournaments", "the tournaments were not sent");
        send(host, JsonCodec.message("questTournamentNext"));
        final JsonObject page = host.awaitMatching("questTournaments", p -> true, "the page was not sent again");
        Assert.assertFalse("playing".equals(host.hello.get("screen").getAsString()));
        final JsonObject bracket = page.getAsJsonObject("bracket");
        Assert.assertTrue(!bracket.has("next") || bracket.get("next").isJsonNull(), "a next match is offered: " + page);
    }

    // Fails if a tournament match adds a quest win or loss, quitting it early is not offered as a forfeit, or the forfeit does not lose the match
    @Test(timeOut = 240_000)
    public void aTournamentMatchIsNoQuestWinOrLoss() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = withTournament(d -> d.setStarted(true));
        final int won = data.getAchievements().getWin();
        final int lost = data.getAchievements().getLost();
        final TestBrowser host = inTournamentMatch(data);
        sessions.onMessage(host, JsonCodec.message("concede"));
        Assert.assertEquals(buttons(host.awaitMatching("campaignResult", r -> true, "no result was sent")), List.of("nextGame", "forfeit"));
        sessions.onMessage(host, message("nextGame", "decision", "QUIT"));
        send(host, JsonCodec.message("leave"));
        host.awaitMatching("questTournaments", p -> true, "leaving did not return to the tournaments");
        final QuestData after = saved(data);
        Assert.assertEquals(after.getAchievements().getWin(), won);
        Assert.assertEquals(after.getAchievements().getLost(), lost);
        // Computer 3, the player's opponent, advances in the player's place
        Assert.assertEquals(draftOf(data).getStandings()[9], "3");
    }

    /** Sets a quest preference from the browser and returns the preferences page sent back. */
    private JsonObject setQuestPref(final TestBrowser host, final String key, final String value) throws InterruptedException {
        send(host, message("campaignPref", "key", key, "value", value));
        return host.awaitMatching("campaignPrefs", p -> true, "the preferences were not sent again");
    }

    // Fails if a decimal preference refuses a decimal value, or a value that Quest's validation refuses is saved
    @Test(timeOut = 120_000)
    public void theDecimalPreferencesAreTakenAndARefusedValueIsNot() throws Exception {
        final QuestPreferences prefs = FModel.getQuestPreferences();
        final String distance = prefs.getPref(QPref.UNLOCK_DISTANCE_MULTIPLIER);
        final String bias = prefs.getPref(QPref.STARTING_POOL_COLOR_BIAS);
        try {
            final TestBrowser host = hostInQuest(QuestFixture.install());
            Assert.assertTrue(setQuestPref(host, "UNLOCK_DISTANCE_MULTIPLIER", "1.5").get("problem") == null);
            Assert.assertEquals(prefs.getPref(QPref.UNLOCK_DISTANCE_MULTIPLIER), "1.5");
            Assert.assertNotNull(setQuestPref(host, "STARTING_POOL_COLOR_BIAS", "0").get("problem"), "a bias of 0 was taken");
            Assert.assertEquals(prefs.getPref(QPref.STARTING_POOL_COLOR_BIAS), bias);
            Assert.assertNotNull(setQuestPref(host, "REWARDS_WINS_MULTIPLIER", "0.5").get("problem"), "a decimal was taken where Quest reads a whole number");
        } finally {
            prefs.setPref(QPref.UNLOCK_DISTANCE_MULTIPLIER, distance);
            prefs.setPref(QPref.STARTING_POOL_COLOR_BIAS, bias);
            prefs.save();
        }
    }

    /** The fixture with a format that allows Magic 2010 and can unlock other sets, 40 wins for unlock tokens, and the given credits. */
    private static QuestData withUnlocks(final long credits) throws Exception {
        final QuestData data = QuestFixture.install();
        // The fixture has no format, so it offers no unlocks. QuestData has no setter for the format
        final Field format = QuestData.class.getDeclaredField("format");
        format.setAccessible(true);
        format.set(data, new GameFormatQuest("Test format", List.of("M10"), List.of(), true));
        for (int i = 0; i < 40; i++) {
            data.getAchievements().addWin();
        }
        data.getAssets().setCredits(credits);
        data.saveData();
        return data;
    }

    // Fails if an unlocked set is not added to the format, or its bonus cards are not sent to reveal
    @Test(timeOut = 120_000)
    public void anUnlockedSetsCardsAreRevealed() throws Exception {
        final QuestData data = withUnlocks(1_000_000);
        final TestBrowser host = hostInQuest(data);
        host.awaitNewest("questDuels", "the duels were not sent");
        send(host, JsonCodec.message("questUnlock"));
        answer(host, host.awaitMatching("hostChoice", q -> "choices".equals(q.get("kind").getAsString()), "no set was offered"), 0);
        answer(host, host.awaitMatching("hostChoice", q -> "confirm".equals(q.get("kind").getAsString()), "the price was not confirmed"), 0);
        final JsonObject reward = host.awaitMatching("reward", r -> true, "the bonus cards were not sent to reveal");
        Assert.assertTrue(reward.getAsJsonArray("steps").asList().stream().anyMatch(s -> "CARDS".equals(s.getAsJsonObject().get("kind").getAsString())),
                "no cards in " + reward);
        Assert.assertEquals(saved(data).getFormat().getAllowedSetCodes().size(), 2);
    }

    // Fails if a bought pet is not owned at level 1 and offered in the Duels page's pet slot, or a pet the credits cannot pay for is bought
    @Test(timeOut = 120_000)
    public void aPetBoughtIsOfferedForDuels() throws Exception {
        final QuestData data = QuestFixture.install();
        final TestBrowser host = hostInQuest(data);
        final JsonObject bird = item(stall(host, "Pet Shop"), "Bird");
        Assert.assertNotNull(bird, "the bird is not for sale");
        FModel.getQuest().getAssets().setCredits(bird.get("price").getAsInt() - 1);
        send(host, message("questBuy", "stall", "Pet Shop", "item", "Bird"));
        host.awaitMatching("notice", n -> true, "a pet too dear was not refused");
        Assert.assertEquals(FModel.getQuest().getAssets().getPetLevel("Bird"), 0);
        FModel.getQuest().getAssets().setCredits(10_000);
        send(host, message("questBuy", "stall", "Pet Shop", "item", "Bird"));
        final JsonObject duels = host.awaitMatching("questDuels", d -> d.toString().contains("Bird"), "the Duels page does not offer the bird");
        Assert.assertEquals(FModel.getQuest().getAssets().getPetLevel("Bird"), 1);
        Assert.assertTrue(duels.getAsJsonArray("pets").toString().contains("Bird"));
    }

    // Fails if a name containing a path finds a file outside the saves
    @Test
    public void aNameWithAPathInItFindsNothing() throws Exception {
        final QuestData data = QuestFixture.install();
        Assert.assertNotNull(QuestGame.find(data.getName()));
        Assert.assertNull(QuestGame.find("../saves/" + data.getName()));
        Assert.assertNull(QuestGame.find("..\\saves\\" + data.getName()));
        Assert.assertNull(QuestGame.find("./" + data.getName()));
        Assert.assertNull(QuestGame.find(null));
    }
}
