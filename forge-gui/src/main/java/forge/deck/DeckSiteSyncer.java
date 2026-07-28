package forge.deck;

import forge.deck.ArchidektDeckUrlProvider.OwnerDeckListing;
import forge.deck.ArchidektDeckUrlProvider.OwnerListingPage;
import forge.util.Localizer;
import forge.util.storage.IStorage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bulk-syncs a deck-site user's public decks into per-username folders under
 * the URL deck store (decks/URL/&lt;username&gt;/). Archidekt only: its owner
 * listing is genuinely unauthenticated; Moxfield's is gated behind a
 * support-issued User-Agent whitelist and is deliberately not attempted.
 *
 * Ground rules (playable-fork worklist item 7):
 * - format is metadata, not a folder level; a re-sync never moves a deck.
 * - decks whose site format id is unmapped go to an Unsorted subfolder at
 *   FIRST import (Unknown-format sentinel, not silently Constructed) and stay
 *   wherever they are on re-sync.
 * - non-conforming and missing-card decks are saved and annotated, never
 *   dropped - it's still your friend's deck.
 * - the site gets one request per unknown-or-changed deck plus the listing
 *   pages, at a polite minimum interval, under a hard cap.
 */
public final class DeckSiteSyncer {
    static final int MAX_DECKS = 200;
    static final int MAX_LISTING_PAGES = 10;
    private static final long REQUEST_INTERVAL_MS = 2000;
    private static final int MISSING_CARDS_LISTED = 6;
    private static final String UNSORTED_FOLDER = "Unsorted";
    private static final Localizer localizer = Localizer.getInstance();

    public record Result(String username, int created, int updated, int unchanged, int unsorted,
            List<String> failures, boolean listingTruncated) {
        public int total() {
            return created + updated + unchanged + failures.size();
        }
    }

    public static Result sync(final String username, final Consumer<String> progress) throws IOException {
        final String folderName = sanitizeFolderName(username);
        final List<OwnerDeckListing> listings = new ArrayList<>();
        boolean truncated = false;

        String pageUrl = ArchidektDeckUrlProvider.getOwnerListingUrl(username);
        for (int page = 0; page < MAX_LISTING_PAGES && pageUrl != null; page++) {
            if (page > 0) {
                politePause();
            }
            final Map<?, ?> root = DeckUrlLoader.readJsonObject(pageUrl, "Archidekt");
            final OwnerListingPage parsed = ArchidektDeckUrlProvider.parseOwnerListingPage(root, username);
            for (final OwnerDeckListing listing : parsed.decks()) {
                if (listings.size() >= MAX_DECKS) {
                    truncated = true;
                    break;
                }
                listings.add(listing);
            }
            pageUrl = truncated ? null : parsed.nextUrl();
            if (pageUrl != null && parsed.nextUrl() != null && page == MAX_LISTING_PAGES - 1) {
                truncated = true;
            }
        }

        if (listings.isEmpty()) {
            throw new IOException(localizer.getMessage("lblDeckSyncNoDecks", username));
        }

        final IStorage<Deck> root = DeckUrlLoader.getStorage();
        final IStorage<Deck> userFolder = root.getFolderOrCreate(folderName);
        //the Unsorted folder is created lazily so users with fully-mapped formats never see it
        IStorage<Deck> unsortedFolder = findExistingSubfolder(userFolder, UNSORTED_FOLDER);

        //existing decks by source key, so re-syncs overwrite in place and never move
        final Map<String, ExistingDeck> existing = new LinkedHashMap<>();
        indexExisting(existing, userFolder);
        if (unsortedFolder != null) {
            indexExisting(existing, unsortedFolder);
        }

        int created = 0, updated = 0, unchanged = 0, unsorted = 0;
        final List<String> failures = new ArrayList<>();
        boolean requestMade = false;

        for (int i = 0; i < listings.size(); i++) {
            final OwnerDeckListing listing = listings.get(i);
            if (progress != null) {
                progress.accept(localizer.getMessage("lblDeckSyncProgress",
                        String.valueOf(i + 1), String.valueOf(listings.size()), listing.name()));
            }

            final ExistingDeck known = existing.get("archidekt:" + listing.deckId());
            if (known != null && listing.updatedAt() != null
                    && Objects.equals(listing.updatedAt(), known.deck.getSyncUpdatedAt())) {
                unchanged++; //no request needed - the site copy hasn't changed since last sync
                continue;
            }

            try {
                if (requestMade) {
                    politePause();
                }
                requestMade = true;

                final IStorage<Deck> targetFolder;
                if (known != null) {
                    targetFolder = known.folder; //never move a deck on re-sync
                } else if (ArchidektDeckUrlProvider.getDeckFormatOrNull(listing.formatValue()) == null) {
                    if (unsortedFolder == null) {
                        unsortedFolder = root.getFolderOrCreate(folderName + "/" + UNSORTED_FOLDER);
                    }
                    targetFolder = unsortedFolder;
                } else {
                    targetFolder = userFolder;
                }

                final String sourceUrl = ArchidektDeckUrlProvider.deckPageUrl(listing.deckId());
                final DeckUrlProvider.RemoteDeck remoteDeck =
                        ArchidektDeckUrlProvider.loadById(listing.deckId(), sourceUrl, targetFolder);
                final List<String> missingCards = new ArrayList<>();
                final Deck deck = DeckUrlLoader.importDeck(remoteDeck, missingCards);
                if (ArchidektDeckUrlProvider.getDeckFormatOrNull(listing.formatValue()) == null) {
                    deck.setDeckFormat(null); //preserve the Unknown sentinel instead of the Constructed fallback
                }
                deck.setSyncUpdatedAt(listing.updatedAt());
                deck.setComment(buildAnnotation(deck, listing, missingCards));

                targetFolder.add(deck);
                if (known == null) {
                    created++;
                    if (targetFolder == unsortedFolder) {
                        unsorted++;
                    }
                    existing.put("archidekt:" + listing.deckId(), new ExistingDeck(deck, targetFolder));
                } else {
                    updated++;
                }
            } catch (final IOException ex) {
                failures.add(listing.name() + ": " + ex.getMessage());
            }
        }

        return new Result(username, created, updated, unchanged, unsorted, failures, truncated);
    }

    /**
     * Annotation, not quarantine: conformance problems and unresolvable cards
     * are recorded in the deck comment. Placement never depends on them, and
     * ENFORCE_DECK_LEGALITY at the lobby stays the authoritative gate.
     */
    private static String buildAnnotation(final Deck deck, final OwnerDeckListing listing, final List<String> missingCards) {
        final List<String> notes = new ArrayList<>();
        if (!missingCards.isEmpty()) {
            final List<String> shown = missingCards.subList(0, Math.min(missingCards.size(), MISSING_CARDS_LISTED));
            String cardList = String.join(", ", shown);
            if (missingCards.size() > shown.size()) {
                cardList += ", +" + (missingCards.size() - shown.size());
            }
            notes.add(localizer.getMessage("lblDeckSyncMissingCards", String.valueOf(missingCards.size()), cardList));
        }
        final DeckFormat format = deck.getDeckFormat();
        if (format == null) {
            notes.add(localizer.getMessage("lblDeckSyncUnknownFormat", String.valueOf(listing.formatValue())));
        } else {
            final String problem = format.getDeckConformanceProblem(deck);
            if (problem != null) {
                notes.add(localizer.getMessage("lblDeckSyncNonConforming", format.name(), problem));
            }
        }
        return notes.isEmpty() ? null : String.join(" | ", notes);
    }

    private static void indexExisting(final Map<String, ExistingDeck> existing, final IStorage<Deck> folder) {
        for (final Deck deck : folder) {
            final String sourceUrl = deck.getSourceUrl();
            if (sourceUrl == null) {
                continue;
            }
            try {
                existing.put("archidekt:" + ArchidektDeckUrlProvider.getDeckId(sourceUrl), new ExistingDeck(deck, folder));
            } catch (final IOException ignored) {
                //deck from another provider or a hand-edited source URL - leave it alone
            }
        }
    }

    private static IStorage<Deck> findExistingSubfolder(final IStorage<Deck> folder, final String name) {
        for (final IStorage<Deck> subfolder : folder.getFolders()) {
            if (name.equalsIgnoreCase(subfolder.getName())) {
                return subfolder;
            }
        }
        return null;
    }

    /** Usernames become directory names; keep only filesystem-safe characters. */
    static String sanitizeFolderName(final String username) {
        final String cleaned = username == null ? "" : username.trim().replaceAll("[^A-Za-z0-9._ @-]", "_");
        if (cleaned.isBlank() || cleaned.equals(UNSORTED_FOLDER)) {
            return "_" + cleaned;
        }
        return cleaned;
    }

    private record ExistingDeck(Deck deck, IStorage<Deck> folder) {
    }

    private static void politePause() throws IOException {
        try {
            Thread.sleep(REQUEST_INTERVAL_MS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Sync interrupted", ex);
        }
    }

    private DeckSiteSyncer() {
    }
}
