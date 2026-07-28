package forge.deck;

import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.deck.ArchidektDeckUrlProvider.OwnerListingPage;

/**
 * Pins the pure parts of deck-site bulk sync (playable-fork worklist item 7):
 * the Archidekt owner-listing parse (fixture shaped from a live probe of
 * /api/decks/v3/?ownerUsername=... on 2026-07-27), the Unknown-format
 * sentinel, and username-to-folder sanitizing.
 */
public class DeckSiteSyncTest {

    //trimmed from the live v3 response: results entries carry id/name/deckFormat/updatedAt
    //and an owner object; next comes back as plain http and must be normalized
    private static final String LISTING_FIXTURE = """
            {"count":3,
             "next":"http://archidekt.com/api/decks/v3/?ownerUsername=Friend&page=2",
             "results":[
               {"id":24755344,"name":"Salt","deckFormat":3,"updatedAt":"2026-07-28T02:45:33.507263Z",
                "owner":{"id":903041,"username":"Friend","moderator":false}},
               {"id":11111111,"name":"Not Mine","deckFormat":3,"updatedAt":"2026-01-01T00:00:00Z",
                "owner":{"id":1,"username":"SomeoneElse"}},
               {"name":"No Id","deckFormat":3,"owner":{"username":"Friend"}},
               {"id":22222222,"name":"Oathbreaker?","deckFormat":14,"updatedAt":null,
                "owner":{"username":"friend"}}
             ]}""";

    @Test
    public void parsesListingSkipsForeignAndMalformedEntries() throws Exception {
        final Map<?, ?> root = (Map<?, ?>) DeckUrlLoader.parseJson(LISTING_FIXTURE);
        final OwnerListingPage page = ArchidektDeckUrlProvider.parseOwnerListingPage(root, "Friend");

        Assert.assertEquals(page.decks().size(), 2, "keeps own decks (case-insensitive), drops foreign and id-less");
        Assert.assertEquals(page.decks().get(0).deckId(), "24755344");
        Assert.assertEquals(page.decks().get(0).name(), "Salt");
        Assert.assertEquals(page.decks().get(0).updatedAt(), "2026-07-28T02:45:33.507263Z");
        Assert.assertEquals(page.decks().get(1).deckId(), "22222222");
        Assert.assertNull(page.decks().get(1).updatedAt(), "null updatedAt survives as null, not a string");
    }

    @Test
    public void nextLinkIsNormalizedToHttps() throws Exception {
        final Map<?, ?> root = (Map<?, ?>) DeckUrlLoader.parseJson(LISTING_FIXTURE);
        final OwnerListingPage page = ArchidektDeckUrlProvider.parseOwnerListingPage(root, "Friend");
        Assert.assertEquals(page.nextUrl(), "https://archidekt.com/api/decks/v3/?ownerUsername=Friend&page=2");
    }

    @Test
    public void missingResultsYieldsEmptyPage() throws Exception {
        final Map<?, ?> root = (Map<?, ?>) DeckUrlLoader.parseJson("{\"count\":0,\"next\":null}");
        final OwnerListingPage page = ArchidektDeckUrlProvider.parseOwnerListingPage(root, "Friend");
        Assert.assertTrue(page.decks().isEmpty());
        Assert.assertNull(page.nextUrl());
    }

    @Test
    public void unknownFormatIdIsASentinelNotConstructed() {
        Assert.assertEquals(ArchidektDeckUrlProvider.getDeckFormatOrNull(3L), DeckFormat.Commander);
        Assert.assertEquals(ArchidektDeckUrlProvider.getDeckFormatOrNull(6L), DeckFormat.Pauper);
        Assert.assertNull(ArchidektDeckUrlProvider.getDeckFormatOrNull(14L), "unmapped id");
        Assert.assertNull(ArchidektDeckUrlProvider.getDeckFormatOrNull(null), "absent");
        Assert.assertNull(ArchidektDeckUrlProvider.getDeckFormatOrNull("3"), "wrong type");
    }

    @Test
    public void usernamesBecomeSafeFolderNames() {
        Assert.assertEquals(DeckSiteSyncer.sanitizeFolderName("Friend"), "Friend");
        Assert.assertEquals(DeckSiteSyncer.sanitizeFolderName("  Friend  "), "Friend");
        Assert.assertEquals(DeckSiteSyncer.sanitizeFolderName("a/../b\\c:d"), "a_.._b_c_d");
        Assert.assertEquals(DeckSiteSyncer.sanitizeFolderName("Unsorted"), "_Unsorted",
                "must not collide with the Unsorted subfolder");
        Assert.assertEquals(DeckSiteSyncer.sanitizeFolderName("boostcesar@gmail.com"), "boostcesar@gmail.com",
                "email-style usernames exist in the wild and round-trip");
    }
}
