package forge.web;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ImageKeys;
import forge.gamemodes.net.WebSocketBytes;
import forge.gamemodes.net.WebSocketTunnel;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.HostingServer;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.sound.MusicPlaylist;
import forge.sound.SoundSystem;
import forge.util.IHasForgeLog;
import forge.util.LogExporter;
import forge.web.images.CardThumbnails;
import forge.web.images.Foil;
import forge.web.images.ImageFiles;
import forge.web.images.SkinSprites;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.PageText;
import forge.web.protocol.Wire;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** The HTTP and WebSocket server on the web port. It serves the page, card images, sounds, the browsers' sockets and the app tunnel. It listens on every interface so that a player on another machine can open a link. The token in the link is new on each launch and keeps everyone else out. */
public final class WebServer implements AutoCloseable, IHasForgeLog {
    private static final String COOKIE = "forge_token";
    /** Whether a request came from a browser on this machine, not from another machine or through a tunnel. */
    private static final AttributeKey<Boolean> LOCAL = AttributeKey.valueOf("forge.local");
    private static final AttributeKey<Boolean> KEEP_ALIVE = AttributeKey.valueOf("forge.keepAlive");
    /** For content that never changes at its address, such as a card picture of one printing or a hashed script chunk. The browser keeps it permanently. */
    private static final String KEEP_FOREVER = "public, max-age=31536000, immutable";
    /** For skin pictures and sound effects. They change only when the skin or sound set is changed and the server restarted, so a day's wait is fair. */
    private static final String KEEP_A_DAY = "max-age=86400";
    /** A stylesheet or script the page names. The page adds a hash of its contents to the address. */
    private static final Pattern PAGE_FILE = Pattern.compile("(href|src)=\"((?:css|js)/[^\"?]+)\"");
    /** A stylesheet link in the page. The page's stylesheets are served joined into one file, STYLES. */
    private static final Pattern STYLESHEET = Pattern.compile("<link rel=\"stylesheet\" href=\"(css/[^\"?]+)\">\\R?");
    private static final String STYLES = "css/page.css";
    /** A chunk the page's script imports. */
    private static final Pattern CHUNK = Pattern.compile("\\./(chunks/[^\"']+\\.js)");
    private static final Pattern BYTE_RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    /** The most bytes that one reply to an audio range request carries: about a minute of music at 128 kbps. */
    private static final int AUDIO_SLICE = 1 << 20;
    /** Keys that failed are stored so they are not fetched again. Past this many, the list is cleared. */
    private static final int MOST_UNAVAILABLE_IMAGES = 10_000;
    /** The same port as Forge's own netplay. Hosting from the desktop client at the same time therefore needs {@code -Dforge.web.port}. */
    private static final int DEFAULT_PORT = 36743;
    /** How long a download is waited on before the browser is told there is no image. */
    private static final int FETCH_TIMEOUT_SECONDS = 15;
    private static final int PING_SECONDS = 30;
    // -Dforge.web.pageDir=<the web resource folder> serves the page from disk, so an edit needs only a reload
    private static final String PAGE_DIR = System.getProperty("forge.web.pageDir");
    private final EventLoopGroup group = new NioEventLoopGroup(2, new DefaultThreadFactory("WebServer", true));
    private final ServerTraffic traffic = new ServerTraffic();
    private final Endpoint endpoint;
    private final String token;
    // The shared fetcher tries a path only once per run and does not call back again, so a key that failed is not retried
    private final Set<String> unavailableImages = ConcurrentHashMap.newKeySet();
    private final Channel channel;
    /** The page as served. It is built once, unless the page is read from a folder that may change. */
    private volatile byte[] page;
    private volatile byte[] styles;

    public interface Endpoint {
        /** The id tells browsers apart across a reload. local says the browser is on this machine. */
        void connected(BrowserChannel channel, String clientId, boolean local);
        void disconnected(BrowserChannel channel);
        void onMessage(BrowserChannel channel, JsonObject message);
    }
    public WebServer(final Endpoint endpoint, final String token) throws InterruptedException {
        this(endpoint, token, configuredPort());
    }

    static int configuredPort() {
        return Integer.getInteger("forge.web.port", DEFAULT_PORT);
    }

    /** A port of 0 uses any free port, which is what a test needs. */
    WebServer(final Endpoint endpoint, final String token, final int port) throws InterruptedException {
        this.endpoint = endpoint;
        this.token = token;
        final ServerBootstrap b = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        // The handlers after the gate depend on who is calling, so the gate adds them at the first request
                        ch.pipeline().addLast(traffic, new HttpServerCodec(), new HttpObjectAggregator(1 << 20), new AccessGate());
                    }
                });
        channel = b.bind(port).sync().channel();
    }

    /** Every byte in and out of the port since it was bound, card images included. */
    ServerTraffic traffic() {
        return traffic;
    }

    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /** The link for this machine's own browser. */
    public String url() {
        return inviteUrl("127.0.0.1");
    }

    /** The link at whichever address a player can reach this machine by. */
    public String inviteUrl(final String address) {
        return "http://" + address + ":" + port() + "/?token=" + token;
    }

    /** The link through an address that leads to this machine, such as the one Cloudflare gives. */
    String inviteUrlThrough(final String origin) {
        return origin + "/?token=" + token;
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        group.shutdownGracefully();
    }

    /**
     * Never waits for the download. Otherwise the thread that serves every other request would wait too.
     * auto means the page asked on its own, not the player, so art that is not cached yet is downloaded only when online image fetching is on.
     */
    private void serveSleeveArt(final ChannelHandlerContext ctx, final String asked, final boolean auto) throws IOException {
        final String key = ImageFiles.artKey(asked);
        final File cached = ImageFiles.sleeveArt(key);
        if (cached != null) {
            respondImage(ctx, cached);
            return;
        }
        // Only a card has art to crop. The fetcher never answers for a token or an effect such as the Ring
        if (!key.startsWith(ImageKeys.CARD_PREFIX) || auto && !FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER)) {
            notFound(ctx);
            return;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        GuiBase.getInterface().invokeInEdtLater(() -> GuiBase.getInterface().getImageFetcher().fetchSleeveArt(key, () -> {
            if (answered.compareAndSet(false, true)) {
                final File fetched = ImageFiles.sleeveArt(key);
                try {
                    if (fetched != null) {
                        respondImage(ctx, fetched);
                        return;
                    }
                } catch (final IOException e) {
                    netLog.warn("Could not send sleeve art {}: {}", key, e.getMessage());
                }
                notFound(ctx);
            }
        }));
        ctx.executor().schedule(() -> {
            if (answered.compareAndSet(false, true)) {
                notFound(ctx);
            }
        }, FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Downloads a missing image and answers the request when it arrives. small asks for the card at the size the board draws it. */
    private void serveImage(final ChannelHandlerContext ctx, final String key, final boolean small) throws IOException {
        final int foil = Foil.seed(key);
        final File file = ImageFiles.card(key);
        if (file != null) {
            respondCardImage(ctx, file, small, foil);
            return;
        }
        if (!ImageFiles.safeKey(key) || unavailableImages.contains(key)
                || !FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER)) {
            notFound(ctx);
            return;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        GuiBase.getInterface().invokeInEdtLater(() -> GuiBase.getInterface().getImageFetcher().fetchImage(key, () -> {
            // Forge remembers a picture it could not find. Without this it would still report the picture as missing now that it exists
            ImageKeys.clearMissingCards();
            final File fetched = ImageFiles.card(key);
            if (answered.compareAndSet(false, true)) {
                try {
                    if (fetched != null) {
                        respondCardImage(ctx, fetched, small, foil);
                    } else {
                        notFound(ctx);
                    }
                } catch (final IOException e) {
                    notFound(ctx);
                }
            }
        }));
        ctx.executor().schedule(() -> {
            if (answered.compareAndSet(false, true)) {
                if (unavailableImages.size() >= MOST_UNAVAILABLE_IMAGES) {
                    unavailableImages.clear();
                }
                unavailableImages.add(key);
                notFound(ctx);
            }
        }, FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void respondCardImage(final ChannelHandlerContext ctx, final File file, final boolean small, final int foil) throws IOException {
        if (!small && foil == 0) {
            respondImage(ctx, file);
            return;
        }
        CardThumbnails.of(file, small, foil).whenComplete((bytes, failed) -> {
            if (bytes != null) {
                respond(ctx, HttpResponseStatus.OK, bytes, ImageFiles.type(file), null, KEEP_FOREVER, null);
                return;
            }
            netLog.warn("Could not draw {}: {}", file, failed.getMessage());
            try {
                respondImage(ctx, file);
            } catch (final IOException e) {
                notFound(ctx);
            }
        });
    }

    private void respondImage(final ChannelHandlerContext ctx, final File file) throws IOException {
        respond(ctx, HttpResponseStatus.OK, Files.readAllBytes(file.toPath()), ImageFiles.type(file), null, KEEP_FOREVER, null);
    }

    /** True when the socket's page came from this server, whichever address the browser reached it by. */
    private static boolean sameOrigin(final FullHttpRequest req) {
        final String origin = req.headers().get(HttpHeaderNames.ORIGIN);
        final String host = req.headers().get(HttpHeaderNames.HOST);
        if (origin == null || host == null) {
            return false;
        }
        final int slashes = origin.indexOf("//");
        return slashes >= 0 && host.equals(origin.substring(slashes + 2));
    }

    /** Whether a token is the link's. The comparison takes constant time, so the time a guess takes reveals nothing about the token. */
    private boolean admits(final String presented) {
        return presented != null && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /** A loopback request that no proxy forwarded. A Cloudflare tunnel also connects from this machine, but adds these headers. */
    private static boolean fromThisMachine(final ChannelHandlerContext ctx, final FullHttpRequest req) {
        return ctx.channel().remoteAddress() instanceof InetSocketAddress a && a.getAddress() != null && a.getAddress().isLoopbackAddress()
                && !req.headers().contains("Cf-Connecting-Ip") && !req.headers().contains("X-Forwarded-For");
    }

    /** A value from the link's query, or null when the link does not carry it. */
    private static String param(final QueryStringDecoder q, final String name) {
        final List<String> values = q.parameters().get(name);
        return values == null ? null : values.get(0);
    }

    /** Whether a request carries the link's token: in its query, or as the cookie that the page load stored. */
    private boolean admitted(final FullHttpRequest req, final QueryStringDecoder q) {
        if (admits(param(q, "token"))) {
            return true;
        }
        final String header = req.headers().get(HttpHeaderNames.COOKIE);
        if (header == null) {
            return false;
        }
        for (final Cookie c : ServerCookieDecoder.STRICT.decode(header)) {
            if (COOKIE.equals(c.name()) && admits(c.value())) {
                return true;
            }
        }
        return false;
    }

    private void respond(final ChannelHandlerContext ctx, final HttpResponseStatus status, final byte[] body, final String type) {
        respond(ctx, status, body, type, null, "no-cache", null);
    }

    private void notFound(final ChannelHandlerContext ctx) {
        respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain");
    }

    /** Forge's logs zipped as desktop's Help menu exports them, or null when there are none. */
    private static byte[] logsZip() throws IOException {
        final Path dir = Files.createTempDirectory("forge-web-logs");
        try {
            final File zip = LogExporter.exportLogs(dir.toFile());
            return zip == null ? null : Files.readAllBytes(zip.toPath());
        } finally {
            final File[] made = dir.toFile().listFiles();
            if (made != null) {
                for (final File f : made) {
                    Files.deleteIfExists(f.toPath());
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    private void respondOrNotFound(final ChannelHandlerContext ctx, final byte[] body, final String type, final String ifNoneMatch) {
        if (body == null) {
            notFound(ctx);
        } else {
            respondUnlessUnchanged(ctx, body, type, null, KEEP_A_DAY, ifNoneMatch);
        }
    }

    /** Answers with the body, or with an empty 304 when the browser already holds the same body. */
    private void respondUnlessUnchanged(final ChannelHandlerContext ctx, final byte[] body, final String type, final String cookieToken,
            final String cacheControl, final String ifNoneMatch) {
        final String etag = etag(body);
        final boolean unchanged = etag.equals(ifNoneMatch);
        respond(ctx, unchanged ? HttpResponseStatus.NOT_MODIFIED : HttpResponseStatus.OK, unchanged ? new byte[0] : body,
                type, cookieToken, cacheControl, etag);
    }

    /**
     * Answers a request. A cookie, when given, is the token that the page was loaded with. The connection stays open when the
     * browser asked for that, because a board of forty pictures would otherwise need forty handshakes.
     */
    private void respond(final ChannelHandlerContext ctx, final HttpResponseStatus status, final byte[] body, final String type,
            final String cookieToken, final String cacheControl, final String etag) {
        final boolean keepAlive = Boolean.TRUE.equals(ctx.channel().attr(KEEP_ALIVE).get());
        final FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, type)
                .set(HttpHeaderNames.CACHE_CONTROL, cacheControl)
                .set(HttpHeaderNames.CONNECTION, keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        // A 304 has no body. A length on it would say that the body the browser already has is empty
        if (status != HttpResponseStatus.NOT_MODIFIED) {
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        }
        if (etag != null) {
            resp.headers().set(HttpHeaderNames.ETAG, etag);
        }
        if (cookieToken != null) {
            final DefaultCookie cookie = new DefaultCookie(COOKIE, cookieToken);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setSameSite(CookieHeaderNames.SameSite.Strict);
            resp.headers().set(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
        }
        send(ctx, resp, keepAlive);
    }

    private void redirect(final ChannelHandlerContext ctx, final String location) {
        final boolean keepAlive = Boolean.TRUE.equals(ctx.channel().attr(KEEP_ALIVE).get());
        final FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        resp.headers()
                .set(HttpHeaderNames.LOCATION, location)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
                .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                .set(HttpHeaderNames.CONNECTION, keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        send(ctx, resp, keepAlive);
    }

    private static void send(final ChannelHandlerContext ctx, final FullHttpResponse resp, final boolean keepAlive) {
        final ChannelFuture sent = ctx.writeAndFlush(resp);
        if (!keepAlive) {
            sent.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String contentType(final String resource) {
        if (resource.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (resource.endsWith(".png")) {
            return "image/png";
        }
        if (resource.endsWith(".jpg")) {
            return "image/jpeg";
        }
        if (resource.endsWith(".webmanifest")) {
            return "application/manifest+json";
        }
        return "application/octet-stream";
    }

    /** A playlist request gets a redirect to one of its tracks by name. The shuffle then stays on the server, and each track is downloaded once. */
    private void serveAudio(final ChannelHandlerContext ctx, final boolean sound, final String name, final String track,
            final String range, final String ifNoneMatch) throws IOException {
        if (sound) {
            respondAudio(ctx, isFileName(name) ? SoundSystem.instance.getSoundResource(name) : null, range, KEEP_A_DAY, ifNoneMatch);
            return;
        }
        final boolean menu = "menu".equals(name);
        final MusicPlaylist playlist = menu ? MusicPlaylist.MENUS : MusicPlaylist.MATCH;
        if (track == null) {
            final String chosen = playlist.getRandomFilename();
            if (chosen == null) {
                notFound(ctx);
            } else {
                redirect(ctx, "/music?name=" + (menu ? "menu" : "match") + "&track="
                        + URLEncoder.encode(new File(chosen).getName(), StandardCharsets.UTF_8));
            }
            return;
        }
        final File dir = SoundSystem.findMusicDirectory(playlist);
        respondAudio(ctx, dir != null && isFileName(track) ? new File(dir, track) : null, range, KEEP_FOREVER, ifNoneMatch);
    }

    private static boolean isFileName(final String name) {
        return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    /** Answers a range one slice at a time, because a media player asks for the rest of a track and often abandons the request. */
    private void respondAudio(final ChannelHandlerContext ctx, final File file, final String range, final String cacheControl,
            final String ifNoneMatch) throws IOException {
        if (file == null || !file.isFile()) {
            notFound(ctx);
            return;
        }
        final long length = file.length();
        final String etag = "\"" + Long.toHexString(file.lastModified()) + "-" + Long.toHexString(length) + "\"";
        // The browser checks its copy before it asks for a range, so an unchanged file is answered before the range is read
        if (etag.equals(ifNoneMatch)) {
            respond(ctx, HttpResponseStatus.NOT_MODIFIED, new byte[0], "audio/mpeg", null, cacheControl, etag);
            return;
        }
        long from = 0;
        long to = length - 1;
        // Several ranges in one request, or a header this does not read, are answered with the whole file
        final Matcher m = range == null ? null : BYTE_RANGE.matcher(range.trim());
        final boolean partial = m != null && m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty());
        if (partial) {
            final Long first = m.group(1).isEmpty() ? null : Longs.tryParse(m.group(1));
            final Long last = m.group(2).isEmpty() ? null : Longs.tryParse(m.group(2));
            if (m.group(1).isEmpty()) {
                from = last == null ? length : Math.max(0, length - last);
            } else {
                from = first == null ? length : first;
                if (last != null) {
                    to = Math.min(to, last);
                }
            }
            to = Math.min(to, from + AUDIO_SLICE - 1);
        }
        final byte[] body;
        if (partial && from > to) {
            body = null;
        } else {
            body = new byte[(int) (to - from + 1)];
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                in.seek(from);
                in.readFully(body);
            }
        }
        final boolean keepAlive = Boolean.TRUE.equals(ctx.channel().attr(KEEP_ALIVE).get());
        final FullHttpResponse resp;
        if (partial && from > to) {
            resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            resp.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes */" + length);
        } else {
            resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    partial ? HttpResponseStatus.PARTIAL_CONTENT : HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            if (partial) {
                resp.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes " + from + "-" + to + "/" + length);
            }
        }
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, file.getName().toLowerCase(Locale.ROOT).endsWith(".wav") ? "audio/wav" : "audio/mpeg")
                .set(HttpHeaderNames.ACCEPT_RANGES, HttpHeaderValues.BYTES)
                .set(HttpHeaderNames.CACHE_CONTROL, cacheControl)
                .set(HttpHeaderNames.ETAG, etag)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes())
                .set(HttpHeaderNames.CONNECTION, keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        send(ctx, resp, keepAlive);
    }

    private static String etag(final byte[] body) {
        final CRC32 crc = new CRC32();
        crc.update(body);
        return "\"" + Long.toHexString(crc.getValue()) + "-" + Integer.toHexString(body.length) + "\"";
    }

    /**
     * The page, with a hash of each stylesheet's and script's contents in its address, so the browser keeps them until they change.
     * Its stylesheets become one file, so a first visit asks for one, and the script's chunks are asked for at once, not one after another.
     */
    private byte[] page() throws IOException {
        final byte[] built = page;
        if (built != null && PAGE_DIR == null) {
            return built;
        }
        final byte[] html = readResource("index.html");
        if (html == null) {
            return null;
        }
        // Joined in the page's order, so the cascade is the same as with separate files. Every stylesheet is in the same folder, so its relative addresses still work
        final ByteArrayOutputStream joined = new ByteArrayOutputStream();
        final StringBuilder linked = new StringBuilder();
        final Matcher sheet = STYLESHEET.matcher(new String(html, StandardCharsets.UTF_8));
        boolean first = true;
        while (sheet.find()) {
            final byte[] css = readResource(sheet.group(1));
            if (css != null) {
                joined.write(css);
                joined.write('\n');
            }
            sheet.appendReplacement(linked, first ? Matcher.quoteReplacement("<link rel=\"stylesheet\" href=\"" + STYLES + "\">\n") : "");
            first = false;
        }
        sheet.appendTail(linked);
        styles = joined.toByteArray();
        final StringBuilder out = new StringBuilder();
        final Matcher file = PAGE_FILE.matcher(linked);
        while (file.find()) {
            final byte[] named = STYLES.equals(file.group(2)) ? styles : readResource(file.group(2));
            final CRC32 crc = new CRC32();
            if (named != null) {
                crc.update(named);
            }
            file.appendReplacement(out, Matcher.quoteReplacement(named == null ? file.group()
                    : file.group(1) + "=\"" + file.group(2) + "?v=" + Long.toHexString(crc.getValue()) + "\""));
        }
        file.appendTail(out);
        final byte[] script = readResource("js/app.js");
        final StringBuilder preloads = new StringBuilder();
        final Matcher chunk = CHUNK.matcher(script == null ? "" : new String(script, StandardCharsets.UTF_8));
        while (chunk.find()) {
            preloads.append("<link rel=\"modulepreload\" href=\"js/").append(chunk.group(1)).append("\">\n");
        }
        final int head = out.indexOf("</head>");
        if (head >= 0) {
            out.insert(head, preloads);
        }
        return page = out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] styles() throws IOException {
        return page() == null ? null : styles;
    }

    private static byte[] readResource(final String resource) throws IOException {
        if (PAGE_DIR != null) {
            final Path root = Path.of(PAGE_DIR).toAbsolutePath().normalize();
            final Path file = root.resolve(resource).normalize();
            return file.startsWith(root) && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private final class AccessGate extends ChannelInboundHandlerAdapter {
        private boolean routed;

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            if (msg instanceof FullHttpRequest req) {
                final QueryStringDecoder q = new QueryStringDecoder(req.uri());
                final boolean socket = "/ws".equals(q.path());
                final boolean netplay = WebSocketTunnel.PATH.equals(q.path());
                // A netplay client is not a page and sends no origin to check. So a cookie must not let it in, and only its link's token does
                final boolean admitted = netplay ? admits(param(q, "token")) : admitted(req, q);
                // The token keeps other pages out. The origin check stops them from opening a socket with a stolen cookie
                if (!admitted || (socket && !sameOrigin(req)) || (netplay && !HostingServer.isHosting())) {
                    req.release();
                    respond(ctx, HttpResponseStatus.FORBIDDEN, new byte[0], "text/plain");
                    return;
                }
                ctx.channel().attr(LOCAL).set(fromThisMachine(ctx, req));
                ctx.channel().attr(KEEP_ALIVE).set(HttpUtil.isKeepAlive(req));
                if (!routed) {
                    routed = true;
                    route(ctx, netplay);
                }
            }
            ctx.fireChannelRead(msg);
        }

        private void route(final ChannelHandlerContext ctx, final boolean netplay) {
            if (netplay) {
                // Desktop or mobile Forge. From here on the host's netplay server owns the connection, as it does for one made in this process
                ServerTraffic.carrying(ctx.channel(), ServerTraffic.Kind.GAME);
                ctx.pipeline().addLast(
                        new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                                .websocketPath(WebSocketTunnel.PATH).checkStartsWith(true)
                                .maxFramePayloadLength(WebSocketTunnel.FRAME_BYTES).build()),
                        new WebSocketBytes());
                FServerManager.getInstance().adopt(ctx.channel());
                return;
            }
            ctx.pipeline().addLast(
                    // State and deck lists are JSON that repeats its field names, so compression matters to a guest over the internet
                    new WebSocketServerCompressionHandler(),
                    new TextCompressor(),
                    new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                            .websocketPath("/ws").checkStartsWith(true).maxFramePayloadLength(1 << 22)
                            .allowExtensions(true).build()),
                    new StaticFiles(),
                    new BrowserSocket(endpoint));
        }
    }

    /**
     * Compresses the page's text for a guest over the internet. Pictures and sound are compressed already, so they are sent as they are.
     * Netty's socket handshake removes this from the pipeline, so it never sees socket frames.
     */
    private static final class TextCompressor extends HttpContentCompressor {
        @Override
        protected Result beginEncode(final HttpResponse response, final String acceptEncoding) throws Exception {
            final String type = response.headers().get(HttpHeaderNames.CONTENT_TYPE, "");
            return type.startsWith("text/") || type.contains("json") || type.startsWith("image/svg") ? super.beginEncode(response, acceptEncoding) : null;
        }
    }

    private final class StaticFiles extends SimpleChannelInboundHandler<FullHttpRequest> {
        /** The path the browser last asked for, so a failure message can name the file. */
        private String asked;

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            if (cause instanceof IOException) {
                netLog.warn("Could not send a browser {}: {}", asked, cause.toString());
            } else {
                netLog.warn(cause, "Could not send a browser {}", asked);
            }
            // Otherwise the browser would wait for a request that is never answered
            if (ctx.channel().isActive()) {
                respond(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, new byte[0], "text/plain");
            }
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest req) throws IOException {
            final QueryStringDecoder q = new QueryStringDecoder(req.uri());
            final String path = q.path();
            final String ifNoneMatch = req.headers().get(HttpHeaderNames.IF_NONE_MATCH);
            asked = path;
            ServerTraffic.carrying(ctx.channel(), "/img".equals(path) || "/sleeveart".equals(path) ? ServerTraffic.Kind.CARD_ART
                    : "/sound".equals(path) || "/music".equals(path) ? ServerTraffic.Kind.AUDIO : ServerTraffic.Kind.PAGE);
            if ("/avatar".equals(path) || "/sleeve".equals(path)) {
                final String index = param(q, "i");
                final Integer i = index == null ? null : Ints.tryParse(index);
                respondOrNotFound(ctx, i == null ? null : SkinSprites.png("/avatar".equals(path), i), "image/png", ifNoneMatch);
                return;
            }
            if ("/sleeveart".equals(path)) {
                final String key = param(q, "key");
                if (key == null) {
                    notFound(ctx);
                } else {
                    serveSleeveArt(ctx, key, "1".equals(param(q, "auto")));
                }
                return;
            }
            if ("/mana".equals(path)) {
                final String symbol = param(q, "s");
                respondOrNotFound(ctx, symbol == null ? null : SkinSprites.manaPng(symbol), "image/png", ifNoneMatch);
                return;
            }
            if ("/ability".equals(path)) {
                final String icon = param(q, "k");
                respondOrNotFound(ctx, icon == null ? null : SkinSprites.abilityPng(icon), "image/png", ifNoneMatch);
                return;
            }
            if ("/coin".equals(path)) {
                respondOrNotFound(ctx, SkinSprites.coinPng(!"tails".equals(param(q, "side"))), "image/png", ifNoneMatch);
                return;
            }
            if ("/skinicon".equals(path)) {
                final String icon = param(q, "k");
                respondOrNotFound(ctx, icon == null ? null : SkinSprites.iconPng(icon), "image/png", ifNoneMatch);
                return;
            }
            if ("/sound".equals(path) || "/music".equals(path)) {
                serveAudio(ctx, "/sound".equals(path), param(q, "name"), param(q, "track"), req.headers().get(HttpHeaderNames.RANGE), ifNoneMatch);
                return;
            }
            if ("/img".equals(path)) {
                final String key = param(q, "key");
                if (key == null) {
                    notFound(ctx);
                } else {
                    serveImage(ctx, key, String.valueOf(CardThumbnails.WIDTH).equals(param(q, "w")));
                }
                return;
            }
            if ("/logs".equals(path)) {
                // The logs are this machine's files, and they name the players who connected, so only a browser on this machine gets them
                if (!Boolean.TRUE.equals(ctx.channel().attr(LOCAL).get())) {
                    respond(ctx, HttpResponseStatus.FORBIDDEN, new byte[0], "text/plain");
                } else {
                    final byte[] zip = logsZip();
                    if (zip == null) {
                        notFound(ctx);
                    } else {
                        respond(ctx, HttpResponseStatus.OK, zip, "application/zip");
                    }
                }
                return;
            }
            if ("/text".equals(path)) {
                respondUnlessUnchanged(ctx, PageText.json(), "application/json; charset=utf-8", null, "no-cache", ifNoneMatch);
                return;
            }
            final String resource = "/".equals(path) ? "index.html" : path.substring(1);
            final byte[] body = resource.contains("..") ? null : "index.html".equals(resource) ? page()
                    : STYLES.equals(resource) ? styles() : readResource(resource);
            final String type = contentType(resource);
            if (body == null) {
                notFound(ctx);
                return;
            }
            // The page stores the link's token as a cookie, so its later requests come in on the same link
            final String token = param(q, "token");
            // A chunk's name, and the version the page adds to its other files, are hashes of the file. A changed file is a new address,
            // so an old one never goes stale. The page itself and files asked for without a version are checked each time.
            final boolean hashed = resource.startsWith("js/chunks/") || q.parameters().containsKey("v");
            respondUnlessUnchanged(ctx, body, type, admits(token) ? token : null, hashed ? KEEP_FOREVER : "no-cache", ifNoneMatch);
        }
    }

    private static final class BrowserSocket extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final Endpoint endpoint;
        private BrowserChannel browser;
        private ScheduledFuture<?> pings;

        BrowserSocket(final Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete done) {
                ServerTraffic.carrying(ctx.channel(), ServerTraffic.Kind.GAME);
                final Channel ch = ctx.channel();
                browser = new BrowserChannel() {
                    @Override
                    public void send(final JsonObject message) {
                        ch.writeAndFlush(new TextWebSocketFrame(Wire.WRITER.toJson(message)));
                    }

                    @Override
                    public void close() {
                        ch.close();
                    }
                };
                final String id = param(new QueryStringDecoder(done.requestUri()), "client");
                endpoint.connected(browser, id == null ? "" : id, Boolean.TRUE.equals(ch.attr(LOCAL).get()));
                // A proxy between the server and the browser drops a socket that stays silent too long, as it does while a player thinks
                pings = ctx.executor().scheduleAtFixedRate(() -> ch.writeAndFlush(new PingWebSocketFrame()),
                        PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
            } else {
                ctx.fireUserEventTriggered(evt);
            }
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame text && browser != null) {
                try {
                    endpoint.onMessage(browser, JsonParser.parseString(text.text()).getAsJsonObject());
                } catch (final RuntimeException e) {
                    netLog.warn(e, "Bad browser message");
                }
            }
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            if (pings != null) {
                pings.cancel(false);
            }
            if (browser != null) {
                endpoint.disconnected(browser);
            }
            ctx.fireChannelInactive();
        }
    }
}
