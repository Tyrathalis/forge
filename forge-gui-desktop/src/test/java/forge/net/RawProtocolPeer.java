package forge.net;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.LobbyUpdateEvent;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.SessionTokenEvent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A peer that speaks the wire protocol with no client logic behind it.
 *
 * <p>Security tests need this rather than an {@code FGameClient} because the
 * threat model is a modified or hand-written client: nothing obliges an
 * attacker to respect what the real client's UI permits, so tests that drive
 * the real client can only ever exercise honest traffic. This sends whatever
 * event it is handed.
 */
final class RawProtocolPeer implements AutoCloseable {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final Channel channel;

    /** Slot index from the most recent lobby update that carried a real one. */
    final AtomicInteger assignedSlot = new AtomicInteger(Integer.MIN_VALUE);
    final AtomicReference<String> token = new AtomicReference<>(null);

    /**
     * Counts down only on a lobby update carrying a real slot. The server
     * broadcasts lobby state on channelActive, before any login, so the first
     * update every peer sees carries the unassigned sentinel (-1); latching on
     * that would race past the login under test.
     */
    final CountDownLatch gotSlotAssignment = new CountDownLatch(1);
    final CountDownLatch gotToken = new CountDownLatch(1);
    /**
     * Fires on game protocol traffic — the observable for "this peer was given
     * the seat's private state", which a rejected peer must never reach.
     */
    final CountDownLatch gotGameState = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    /** Any lobby update at all, including one carrying the unassigned sentinel. */
    final CountDownLatch gotAnyLobbyUpdate = new CountDownLatch(1);
    final AtomicReference<GameLobbyData> lobbyData = new AtomicReference<>(null);

    RawProtocolPeer(final int port) throws InterruptedException {
        final Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(final SocketChannel ch) {
                        ch.pipeline().addLast(
                                new CompatibleObjectEncoder(null),
                                new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)),
                                new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                                        if (msg instanceof SessionTokenEvent e) {
                                            token.set(e.getToken());
                                            gotToken.countDown();
                                        } else if (msg instanceof LobbyUpdateEvent e) {
                                            lobbyData.set(e.getState());
                                            gotAnyLobbyUpdate.countDown();
                                            if (e.getSlot() >= 0) {
                                                assignedSlot.set(e.getSlot());
                                                gotSlotAssignment.countDown();
                                            }
                                        } else if (msg instanceof GuiGameEvent) {
                                            gotGameState.countDown();
                                        }
                                    }

                                    @Override
                                    public void channelInactive(final ChannelHandlerContext ctx) {
                                        closed.countDown();
                                    }
                                });
                    }
                });
        channel = b.connect("127.0.0.1", port).sync().channel();
    }

    void login(final String username, final String reconnectToken) {
        channel.writeAndFlush(new LoginEvent(username, 0, 0, "test", false, reconnectToken));
    }

    /** Send an arbitrary event, including one an honest client would never construct. */
    void send(final NetEvent event) {
        channel.writeAndFlush(event);
    }

    @Override
    public void close() {
        channel.close();
        group.shutdownGracefully();
    }
}
