package forge.web.deck;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.web.WebTestSupport;
import forge.web.lobby.LobbyFormats;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

public class LegalityTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static PaperCard card(final String name) {
        return StaticData.instance().getCommonCards().getCard(name);
    }

    private static Deck commanderDeck(final String commander) {
        final Deck d = new Deck("t");
        d.getOrCreate(DeckSection.Commander).add(card(commander), 1);
        return d;
    }

    // Fails if a commander format's card list is offered as a pool for a 60-card Constructed game
    @Test
    public void commanderFormatsAreNotConstructedCardPools() {
        final List<String> offered = new ArrayList<>();
        LobbyFormats.cardPools().groups().forEach(g -> offered.addAll(g.formats()));
        Assert.assertTrue(offered.contains("Premodern"), "a casual Constructed format is missing: " + offered);
        for (final String name : List.of("Commander", "Brawl", "Oathbreaker", "PreDH")) {
            Assert.assertFalse(offered.contains(name), name + " is offered as a Constructed card pool");
        }
    }

    // Fails if a Commander ban is missed. getDeckConformanceProblem does not check bans
    @Test
    public void commanderBanIsFlagged() {
        final Deck d = commanderDeck("Meren of Clan Nel Toth");
        d.getMain().add(card("Mana Crypt"), 1);
        final Legality.Result r = Legality.check(d, Check.of(GameType.Commander, null));
        Assert.assertEquals(r.flags().get("Mana Crypt"), "banned in Commander");
    }

    // Fails if No restriction applies a format's rules
    @Test
    public void noRestrictionOnlyCountsCopies() {
        final Deck d = commanderDeck("Meren of Clan Nel Toth");
        d.getMain().add(card("Mana Crypt"), 1);
        Assert.assertNull(Legality.check(d, Check.none()).verdict());
    }

    // Fails if a card pool's message no longer names the cards it rejects, or names a restricted card as illegal
    @Test
    public void cardPoolNamesWhatItRejects() {
        final Deck d = new Deck("t");
        d.getMain().add(card("Black Lotus"), 2);
        d.getMain().add(card("Forest"), 20);
        Assert.assertEquals(DeckCatalog.poolProblem(FModel.getFormats().get("Modern"), d), "Not legal in Modern: 1 card. Black Lotus.");
        Assert.assertEquals(DeckCatalog.poolProblem(FModel.getFormats().get("Vintage"), d), "Vintage allows one copy of Black Lotus.");
        d.getMain().remove(card("Black Lotus"), 1);
        Assert.assertNull(DeckCatalog.poolProblem(FModel.getFormats().get("Vintage"), d));
    }
}
