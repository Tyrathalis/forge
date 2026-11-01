package forge.web.deck;

import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.web.WebTestSupport;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class DeckFileTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    // Fails if a deck file loses a card, its printing or its foil, in any card line format Forge has written
    @Test
    public void everyCardLineFormIsRead() {
        final DeckSession.FileDeck read = DeckSession.readFile("[metadata]\nName=Mini\n[Main]\n4 Lightning Bolt|M10\n3 Shock|M10|1\n"
                + "2 Forest|M10|[246]\n1 Counterspell+|7ED\n[Sideboard]\n2 Duress|M10|[96]\n");
        Assert.assertEquals(read.deck().getName(), "Mini");
        Assert.assertEquals(read.deck().getMain().countAll(), 10);
        Assert.assertEquals(read.deck().get(DeckSection.Sideboard).countAll(), 2);
        Assert.assertEquals(read.leftOut(), 0);
        final PaperCard forest = read.deck().getMain().toFlatList().stream().filter(c -> c.getName().equals("Forest")).findFirst().orElseThrow();
        Assert.assertEquals(forest.getEdition(), "M10");
        Assert.assertEquals(forest.getCollectorNumber(), "246");
        Assert.assertTrue(read.deck().getMain().toFlatList().stream().anyMatch(c -> c.getName().equals("Counterspell") && c.isFoil()));
    }

    // Fails if a card unknown to Forge stops the deck from being read, or is dropped without being counted
    @Test
    public void unknownCardsAreCounted() {
        final DeckSession.FileDeck read = DeckSession.readFile("[metadata]\nName=Mini\n[Main]\n4 Lightning Bolt|M10\n3 No Such Card Anywhere|M10\n");
        Assert.assertEquals(read.deck().getMain().countAll(), 4);
        Assert.assertEquals(read.leftOut(), 3);
    }

    // Fails if a pasted list is treated as a deck file. It would then be saved without the importer reading it
    @Test
    public void plainListIsNotADeckFile() {
        Assert.assertNull(DeckSession.readFile("4 Lightning Bolt\n20 Mountain\n"));
    }

    // Fails if the importer loses a card, its printing or its foil when it is given a deck file
    @Test
    public void importerReadsADeckFile() {
        final DeckImport.Read read = DeckImport.read("[metadata]\nName=Mini\n[Main]\n4 Lightning Bolt|M10\n3 Shock|M10|1\n"
                + "2 Forest|M10|[246]\n1 Counterspell+|7ED\n[Sideboard]\n2 Duress|M10|[96]\n", Check.of(GameType.Constructed, null));
        Assert.assertEquals(read.name(), "Mini");
        Assert.assertEquals(read.deck().getMain().countAll(), 10);
        Assert.assertEquals(read.deck().get(DeckSection.Sideboard).countAll(), 2);
        Assert.assertEquals(read.notImported(), 0);
        Assert.assertEquals(read.lines().stream().map(l -> l.kind().name()).toList().subList(0, 9),
                java.util.List.of("ignored", "heading", "heading", "read", "read", "read", "read", "heading", "read"));
        final PaperCard forest = read.deck().getMain().toFlatList().stream().filter(c -> c.getName().equals("Forest")).findFirst().orElseThrow();
        Assert.assertEquals(forest.getCollectorNumber(), "246");
        Assert.assertTrue(read.deck().getMain().toFlatList().stream().anyMatch(c -> c.getName().equals("Counterspell") && c.isFoil()));
    }

    // Fails if a deck file's card unknown to Forge is imported as a stand-in, or dropped without a problem being reported
    @Test
    public void importerMarksADeckFilesUnknownCard() {
        final DeckImport.Read read = DeckImport.read("[metadata]\nName=Mini\n[Main]\n4 Lightning Bolt|M10\n3 No Such Card Anywhere|M10\n",
                Check.of(GameType.Constructed, null));
        Assert.assertEquals(read.deck().getMain().countAll(), 4);
        Assert.assertEquals(read.notImported(), 1);
        Assert.assertEquals(read.lines().get(4).kind().name(), "problem");
        Assert.assertTrue(read.problems().stream().anyMatch(p -> p.line() == 4));
    }
}
