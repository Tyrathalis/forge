package forge.gamemodes.chronicle.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.HashMap;

/**
 * Key-value save container for Chronicle mode: each value is independently
 * serialized to its own byte[], so a corrupt or unknown key cannot poison the
 * rest of the map and readers can probe with containsKey before reading.
 *
 * Headless port of Adventure's SaveFileData (forge-gui-mobile), which cannot
 * be used from forge-gui: module dependency direction plus libGDX types. The
 * Pixmap/Vector2/Rectangle overloads are dropped; everything else keeps the
 * same wire behavior, including the "IOException" sentinel key (checked by
 * ChronicleSaveIO before committing a file) and the serialVersionUID-tolerant
 * DecompressibleInputStream.
 */
public class ChronicleSaveData extends HashMap<String, byte[]> {
    private static final long serialVersionUID = 1L;

    /** Magic key inserted on any store() failure; a map containing it must never be written to disk. */
    public static final String ERROR_SENTINEL_KEY = "IOException";

    public boolean hasStoreError() {
        return containsKey(ERROR_SENTINEL_KEY);
    }

    public String getStoreError() {
        byte[] raw = get(ERROR_SENTINEL_KEY);
        return raw == null ? null : new String(raw);
    }

    public void store(String key, ChronicleSaveData subData) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ObjectOutputStream objStream = new ObjectOutputStream(stream);
            objStream.writeObject(subData);
            objStream.flush();
            put(key, stream.toByteArray());
        } catch (IOException e) {
            put(ERROR_SENTINEL_KEY, e.toString().getBytes());
            e.printStackTrace();
        }
    }

    public void store(String key, float subData) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ObjectOutputStream objStream = new ObjectOutputStream(stream);
            objStream.writeFloat(subData);
            objStream.flush();
            put(key, stream.toByteArray());
        } catch (IOException e) {
            put(ERROR_SENTINEL_KEY, e.toString().getBytes());
            e.printStackTrace();
        }
    }

    public void store(String key, double subData) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ObjectOutputStream objStream = new ObjectOutputStream(stream);
            objStream.writeDouble(subData);
            objStream.flush();
            put(key, stream.toByteArray());
        } catch (IOException e) {
            put(ERROR_SENTINEL_KEY, e.toString().getBytes());
            e.printStackTrace();
        }
    }

    public void store(String key, int subData) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ObjectOutputStream objStream = new ObjectOutputStream(stream);
            objStream.writeInt(subData);
            objStream.flush();
            put(key, stream.toByteArray());
        } catch (IOException e) {
            put(ERROR_SENTINEL_KEY, e.toString().getBytes());
            e.printStackTrace();
        }
    }

    public void store(String key, long subData) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ObjectOutputStream objStream = new ObjectOutputStream(stream);
            objStream.writeLong(subData);
            objStream.flush();
            put(key, stream.toByteArray());
        } catch (IOException e) {
            put(ERROR_SENTINEL_KEY, e.toString().getBytes());
            e.printStackTrace();
        }
    }

    public void store(String key, boolean subData) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ObjectOutputStream objStream = new ObjectOutputStream(stream);
            objStream.writeBoolean(subData);
            objStream.flush();
            put(key, stream.toByteArray());
        } catch (IOException e) {
            put(ERROR_SENTINEL_KEY, e.toString().getBytes());
            e.printStackTrace();
        }
    }

    public void store(String key, String subData) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ObjectOutputStream objStream = new ObjectOutputStream(stream);
            objStream.writeUTF(subData);
            objStream.flush();
            put(key, stream.toByteArray());
        } catch (IOException e) {
            put(ERROR_SENTINEL_KEY, e.toString().getBytes());
            e.printStackTrace();
        }
    }

    public void storeObject(String key, Object subData) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ObjectOutputStream objStream = new ObjectOutputStream(stream);
            objStream.writeObject(subData);
            objStream.flush();
            put(key, stream.toByteArray());
        } catch (IOException e) {
            put(ERROR_SENTINEL_KEY, e.toString().getBytes());
            e.printStackTrace();
        }
    }

    public ChronicleSaveData readSubData(String key) {
        if (!containsKey(key))
            return null;
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(get(key));
            ObjectInputStream objStream = new DecompressibleInputStream(stream);
            return (ChronicleSaveData) objStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Object readObject(String key) {
        if (!containsKey(key))
            return null;
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(get(key));
            ObjectInputStream objStream = new DecompressibleInputStream(stream);
            return objStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        } catch (ClassCastException e) {
            System.err.println("Chronicle save: problem loading object: " + key);
        }
        return null;
    }

    public String readString(String key) {
        if (!containsKey(key))
            return null;
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(get(key));
            ObjectInputStream objStream = new DecompressibleInputStream(stream);
            return objStream.readUTF();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public long readLong(String key) {
        if (!containsKey(key))
            return 0;
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(get(key));
            ObjectInputStream objStream = new DecompressibleInputStream(stream);
            return objStream.readLong();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public float readFloat(String key) {
        if (!containsKey(key))
            return 0.0f;
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(get(key));
            ObjectInputStream objStream = new DecompressibleInputStream(stream);
            return objStream.readFloat();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0.0f;
    }

    public double readDouble(String key) {
        if (!containsKey(key))
            return 0.0;
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(get(key));
            ObjectInputStream objStream = new DecompressibleInputStream(stream);
            return objStream.readDouble();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0.0;
    }

    public int readInt(String key) {
        if (!containsKey(key))
            return 0;
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(get(key));
            ObjectInputStream objStream = new DecompressibleInputStream(stream);
            return objStream.readInt();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public boolean readBool(String key) {
        if (!containsKey(key))
            return false;
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(get(key));
            ObjectInputStream objStream = new DecompressibleInputStream(stream);
            return objStream.readBoolean();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * ObjectInputStream that substitutes the local class descriptor when the
     * stream's serialVersionUID no longer matches — old saves survive class
     * evolution instead of dying with InvalidClassException.
     */
    static class DecompressibleInputStream extends ObjectInputStream {
        public DecompressibleInputStream(InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
            ObjectStreamClass resultClassDescriptor = super.readClassDescriptor();
            Class<?> localClass;
            try {
                localClass = Class.forName(resultClassDescriptor.getName());
            } catch (ClassNotFoundException e) {
                System.err.println("Chronicle save: no local class for " + resultClassDescriptor.getName());
                return resultClassDescriptor;
            }
            ObjectStreamClass localClassDescriptor = ObjectStreamClass.lookup(localClass);
            if (localClassDescriptor != null) {
                final long localSUID = localClassDescriptor.getSerialVersionUID();
                final long streamSUID = resultClassDescriptor.getSerialVersionUID();
                if (streamSUID != localSUID) {
                    System.err.println("Chronicle save: overriding serialized class version mismatch: local serialVersionUID = "
                            + localSUID + " stream serialVersionUID = " + streamSUID);
                    resultClassDescriptor = localClassDescriptor;
                }
            }
            return resultClassDescriptor;
        }
    }
}
