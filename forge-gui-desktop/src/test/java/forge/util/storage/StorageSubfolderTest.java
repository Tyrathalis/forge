package forge.util.storage;

import java.io.File;
import java.nio.file.Files;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.deck.Deck;
import forge.deck.io.DeckStorage;

/**
 * Pins subfolder creation on the deck stores. Deck-site sync files decks under
 * per-username folders via getFolderOrCreate, which is the first live caller of
 * that path: stock StorageNestedFolders.add was an unimplemented stub, and
 * getOrCreateSubfolder built the child unit on the parent's serializer.
 */
public class StorageSubfolderTest {

    private static StorageImmediatelySerialized<Deck> openStore(File root) {
        return new StorageImmediatelySerialized<>("URL decks",
                new DeckStorage(root, root.getParent()), true);
    }

    @Test
    public void getFolderOrCreateCreatesAndFilesUnderSubfolders() throws Exception {
        final File root = Files.createTempDirectory("urlstore").toFile();
        try {
            final StorageImmediatelySerialized<Deck> store = openStore(root);

            final IStorage<Deck> user = store.getFolderOrCreate("SomeUser");
            user.add(new Deck("Test Deck"));
            Assert.assertTrue(new File(root, "SomeUser" + File.separator + "Test Deck.dck").isFile(),
                    "deck must be saved inside the subfolder, not the parent");

            //the lazy Unsorted/ path nests a folder under the freshly-created one
            final IStorage<Deck> unsorted = store.getFolderOrCreate("SomeUser/Unsorted");
            unsorted.add(new Deck("Odd Deck"));
            Assert.assertTrue(new File(root, "SomeUser" + File.separator + "Unsorted" + File.separator + "Odd Deck.dck").isFile());

            //asking again returns the same live unit, not a fresh empty one
            Assert.assertSame(store.getFolderOrCreate("SomeUser"), user);
            Assert.assertNotNull(user.get("Test Deck"));

            //a reload from disk sees the same layout
            final StorageImmediatelySerialized<Deck> reload = openStore(root);
            Assert.assertNotNull(reload.tryGetFolder("SomeUser"));
            Assert.assertNotNull(reload.tryGetFolder("SomeUser").get("Test Deck"));
            Assert.assertNotNull(reload.tryGetFolder("SomeUser/Unsorted").get("Odd Deck"));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void deleteFolderRemovesDecksSubfoldersAndDirectory() throws Exception {
        final File root = Files.createTempDirectory("urlstore").toFile();
        try {
            final StorageImmediatelySerialized<Deck> store = openStore(root);
            store.add(new Deck("Root Deck"));
            final IStorage<Deck> user = store.getFolderOrCreate("SomeUser");
            user.add(new Deck("Deck A"));
            user.add(new Deck("Deck B"));
            store.getFolderOrCreate("SomeUser/Unsorted").add(new Deck("Odd Deck"));

            Assert.assertEquals(forge.deck.DeckUrlLoader.deleteFolder(openStore(root), "SomeUser"), 3);
            Assert.assertFalse(new File(root, "SomeUser").exists(), "folder directory must be removed");
            Assert.assertTrue(new File(root, "Root Deck.dck").isFile(), "decks outside the folder are untouched");

            final StorageImmediatelySerialized<Deck> reload = openStore(root);
            Assert.assertNull(reload.tryGetFolder("SomeUser"));
            Assert.assertNotNull(reload.get("Root Deck"));

            Assert.assertEquals(forge.deck.DeckUrlLoader.deleteFolder(reload, "NoSuchUser"), -1);
        } finally {
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(File dir) {
        final File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        dir.delete();
    }
}
