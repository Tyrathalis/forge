package forge.gamemodes.net;

import forge.trackable.Tracker;
import io.netty.handler.codec.serialization.ClassResolver;

import java.io.*;

/**
 * {@link ObjectInputStream} subclass used by {@link CompatibleObjectDecoder}
 * for the payload of every network message. Mirrors {@link CObjectOutputStream}:
 *
 * <ul>
 *   <li>Reads the <b>thin class descriptor</b> (one-byte type tag + UTF class
 *       name) the sender wrote, and looks up the local {@code ObjectStreamClass}
 *       via the {@link ClassResolver}. Sender-side {@code serialVersionUID}
 *       and field metadata are not on the wire — both ends must hold matching
 *       class definitions.</li>
 *   <li>When a Tracker is set, delegates {@code resolveObject} to
 *       {@link TrackableSerializer#resolve}, turning incoming
 *       {@link TrackableSerializer.IdRef} markers back into live CardView/
 *       PlayerView instances from the Tracker.</li>
 * </ul>
 */
public class CObjectInputStream extends ObjectInputStream implements forge.util.IHasForgeLog {
    private final ClassResolver classResolver;
    private final Tracker tracker;

    /**
     * Resolution is enabled whenever a tracker is present. The encoder's
     * replacement, by contrast, is gated per-message via the
     * {@code replaceTrackables} flag — the encoder knows which messages
     * carry compressible references; the decoder does not, so it stays
     * ready for any frame.
     */
    CObjectInputStream(InputStream in, ClassResolver classResolver, Tracker tracker) throws IOException {
        super(in);
        this.classResolver = classResolver;
        this.tracker = tracker;
        if (tracker != null) {
            enableResolveObject(true);
        }
        WireArrayLimit.applyTo(this);
    }

    /**
     * Both overrides below consult {@link WireClassFilter} before the name is
     * turned into a Class, so a rejected class is never loaded.
     */
    @Override
    protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
        int type = read();
        if (type < 0)
            throw new EOFException();
        else if (type == CObjectOutputStream.TYPE_THIN_DESCRIPTOR) {
            final String name = readUTF();
            WireClassFilter.checkAllowed(name);
            return ObjectStreamClass.lookupAny(classResolver.resolve(name));
        } else {
            final ObjectStreamClass desc = super.readClassDescriptor();
            WireClassFilter.checkAllowed(desc.getName());
            return desc;
        }
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
        WireClassFilter.checkAllowed(desc.getName());
        Class<?> clazz;
        try {
            clazz = classResolver.resolve(desc.getName());
        } catch (ClassNotFoundException ignored) {
            clazz = super.resolveClass(desc);
        }
        return clazz;
    }

    /**
     * Refuse dynamic proxies outright.
     *
     * <p>This is not redundant with the checks above. A proxy class descriptor
     * ({@code TC_PROXYCLASSDESC}) does not travel as a class name, so it never
     * reaches {@code readClassDescriptor} or {@code resolveClass} — the JDK
     * reads the interface list and calls this method instead. Filtering only
     * the other two would leave the stream able to synthesise a proxy backed
     * by an attacker-chosen {@code InvocationHandler}, which is the entry point
     * for the best-known deserialization chains.
     *
     * <p>Nothing in the protocol carries a proxy: none appeared anywhere in
     * the measured wire traffic, and the message types are concrete view and
     * event classes. So the safe answer is simply no.
     */
    @Override
    protected Class<?> resolveProxyClass(String[] interfaces) throws IOException, ClassNotFoundException {
        netLog.error("Rejected dynamic proxy on the wire implementing {} — "
                + "the multiplayer protocol does not carry proxies", String.join(", ", interfaces));
        if (WireClassFilter.isEnforcing()) {
            throw new InvalidClassException("dynamic proxy",
                    "proxy classes are not permitted by the multiplayer class filter");
        }
        return super.resolveProxyClass(interfaces);
    }

    @Override
    protected Object resolveObject(Object obj) throws IOException {
        return TrackableSerializer.resolve(obj, tracker);
    }
}
