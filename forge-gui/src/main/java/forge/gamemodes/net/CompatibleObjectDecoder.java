package forge.gamemodes.net;

import forge.trackable.Tracker;
import forge.util.IHasForgeLog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.serialization.ClassResolver;
import net.jpountz.lz4.LZ4BlockInputStream;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.StreamCorruptedException;

/**
 * Netty inbound handler that reads the 4-byte length frame and deserializes
 * one network message per call. The payload is passed to a
 * {@link CObjectInputStream} for LZ4 decompression and Java deserialization;
 * tracker-aware reference resolution ({@link TrackableSerializer.IdRef} →
 * live CardView/PlayerView) happens inside that stream via {@code resolveObject}.
 */
public class CompatibleObjectDecoder extends LengthFieldBasedFrameDecoder implements IHasForgeLog {

    private static final int SLOW_DECODE_LOG_THRESHOLD_MS = 50;

    /**
     * Ceiling on bytes read out of the LZ4 stream for a single frame.
     *
     * <p>The frame length check bounds the <b>compressed</b> payload at about
     * 9.5 MiB, which says nothing about how far LZ4 expands it. Real traffic is
     * nowhere near this — a whole 16-turn game measured about 250 KB across all
     * of its deltas — so this default is generous by orders of magnitude and
     * exists only to make the pathological case terminate.
     *
     * <p>This bounds bytes <i>read</i>, and so does not address a declared but
     * unread allocation: {@code ObjectInputStream} sizes an array from the
     * length the peer declares before reading any elements. That is
     * {@link WireStreamLimits}'s job, not this one.
     */
    private static final long MAX_DECOMPRESSED_BYTES =
            Long.getLong("forge.net.maxDecompressedBytes", 64L * 1024 * 1024);

    /** Fails the read rather than letting a decompression bomb size the heap. */
    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long consumed;

        BoundedInputStream(final InputStream in, final long limit) {
            super(in);
            this.limit = limit;
        }

        private void count(final long n) throws IOException {
            if (n <= 0) {
                return;
            }
            consumed += n;
            if (consumed > limit) {
                throw new IOException("Decompressed frame exceeds " + limit
                        + " bytes; refusing to continue reading");
            }
        }

        @Override
        public int read() throws IOException {
            final int b = super.read();
            count(b < 0 ? 0 : 1);
            return b;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            final int n = super.read(b, off, len);
            count(n);
            return n;
        }

        @Override
        public long skip(final long n) throws IOException {
            final long skipped = super.skip(n);
            count(skipped);
            return skipped;
        }
    }

    private final ClassResolver classResolver;
    private volatile Tracker tracker;

    public CompatibleObjectDecoder(int maxObjectSize, ClassResolver classResolver) {
        // LengthFieldBasedFrameDecoder: maxFrameLength, lengthFieldOffset=0,
        // lengthFieldLength=4, lengthAdjustment=0, initialBytesToStrip=4.
        super(maxObjectSize, 0, 4, 0, 4);
        this.classResolver = classResolver;
    }

    public void setTracker(Tracker tracker) {
        this.tracker = tracker;
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }
        int frameSize = frame.readableBytes();
        long startMs = System.currentTimeMillis();

        ObjectInputStream objectIn = new CObjectInputStream(
                new BoundedInputStream(
                        new LZ4BlockInputStream(new ByteBufInputStream(frame, true)),
                        MAX_DECOMPRESSED_BYTES),
                this.classResolver, tracker);

        Object result = null;
        try {
            result = objectIn.readObject();
        } catch (StreamCorruptedException e) {
            netLog.error("Version Mismatch: {}", e.getMessage());
        } catch (InvalidClassException e) {
            // A peer named a class the protocol does not carry, or asked for
            // more of one than WireStreamLimits allows. Drop the frame rather
            // than tearing the pipeline down, matching a corrupt frame.
            netLog.error("Dropping refused frame: {}", e.getMessage());
        } finally {
            objectIn.close();
        }

        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed > SLOW_DECODE_LOG_THRESHOLD_MS
                || frameSize > CompatibleObjectEncoder.LARGE_MESSAGE_LOG_THRESHOLD_BYTES) {
            netLog.info("Decoded {} in {} ms ({} bytes compressed)",
                    result != null ? result.getClass().getSimpleName() : "null", elapsed, frameSize);
        }

        return result;
    }

}
