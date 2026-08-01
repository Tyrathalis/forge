package forge.gamemodes.chronicle.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Chronicle save-file container: GZIP stream holding
 * [format version int][header ChronicleSaveData][main ChronicleSaveData].
 *
 * The header is written first so a slot browser can read name/day/date and
 * stop without inflating the whole save. The write path uses the Adventure
 * backup protocol: rename the existing file aside, refuse to commit a map
 * carrying the store-error sentinel, and restore the backup on any failure.
 *
 * All methods take explicit File/dir arguments — tests point them at temp
 * directories; production callers use ChronicleSaveIO.defaultSaveDir().
 */
public final class ChronicleSaveIO {

    /** Bump on any schema change; add a step to migrate() in the same commit. */
    public static final int CURRENT_VERSION = 1;

    public static final String AUTO_SAVE_NAME = "autosave.sav";

    private ChronicleSaveIO() {
    }

    /** Loaded save: format version as read from disk (pre-migration), header and main blocks. */
    public static final class Loaded {
        public final int version;
        public final ChronicleSaveData header;
        public final ChronicleSaveData main;

        Loaded(int version, ChronicleSaveData header, ChronicleSaveData main) {
            this.version = version;
            this.header = header;
            this.main = main;
        }
    }

    public static File slotFile(File saveDir, int slot) {
        return new File(saveDir, slot < 0 ? AUTO_SAVE_NAME : slot + "_chronicle.sav");
    }

    /** All save files in the directory, autosave included; empty list if the dir doesn't exist. */
    public static List<File> listSaves(File saveDir) {
        List<File> result = new ArrayList<>();
        File[] files = saveDir.listFiles((dir, name) -> name.endsWith(".sav"));
        if (files != null) {
            for (File f : files) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Write header+main to file with backup protection. Returns false (leaving
     * any previous save intact) if either block carries the store-error
     * sentinel or the write fails.
     */
    public static boolean save(File file, ChronicleSaveData header, ChronicleSaveData main) {
        if (header.hasStoreError() || main.hasStoreError()) {
            System.err.println("Chronicle save aborted, store error: "
                    + (header.hasStoreError() ? header.getStoreError() : main.getStoreError()));
            return false;
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            System.err.println("Chronicle save aborted, cannot create " + dir);
            return false;
        }
        File backup = new File(file.getAbsolutePath() + ".old");
        if (file.exists()) {
            if (backup.exists()) {
                backup.delete();
            }
            if (!file.renameTo(backup)) {
                System.err.println("Chronicle save aborted, cannot back up " + file);
                return false;
            }
        }
        try (ObjectOutputStream out = new ObjectOutputStream(
                new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(file))))) {
            out.writeInt(CURRENT_VERSION);
            out.writeObject(header);
            out.writeObject(main);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
            file.delete();
            if (backup.exists() && !backup.renameTo(file)) {
                System.err.println("Chronicle save: backup restore ALSO failed for " + file);
            }
            return false;
        }
        backup.delete();
        return true;
    }

    /** Full load; returns null on any failure. Runs the migration ladder before returning. */
    public static Loaded load(File file) {
        try (ObjectInputStream in = new ObjectInputStream(
                new GZIPInputStream(new BufferedInputStream(new FileInputStream(file))))) {
            int version = in.readInt();
            ChronicleSaveData header = (ChronicleSaveData) in.readObject();
            ChronicleSaveData main = (ChronicleSaveData) in.readObject();
            migrate(version, header, main);
            return new Loaded(version, header, main);
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            System.err.println("Chronicle save: failed to load " + file);
            e.printStackTrace();
            return null;
        }
    }

    /** Header-only load for the slot browser; returns null on any failure. */
    public static Loaded readHeader(File file) {
        try (ObjectInputStream in = new ObjectInputStream(
                new GZIPInputStream(new BufferedInputStream(new FileInputStream(file))))) {
            int version = in.readInt();
            ChronicleSaveData header = (ChronicleSaveData) in.readObject();
            return new Loaded(version, header, null);
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            return null;
        }
    }

    /**
     * Version ladder, Quest-style: each step upgrades data in place and falls
     * through to the next. Services additionally probe with containsKey on
     * every read, so most additive changes need no step here at all.
     */
    private static void migrate(int fromVersion, ChronicleSaveData header, ChronicleSaveData main) {
        // if (fromVersion < 2) { ... }
    }
}
