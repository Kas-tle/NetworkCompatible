package dev.kastle.netty.channel.nethernet.signaling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class NetherNetXboxSignaling extends SimpleChannelInboundHandler<TextWebSocketFrame> implements NetherNetSignaling {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetXboxSignaling.class);
    private static final Gson gson = new Gson();

    private final String xboxToken;
    private final String localNetworkId;
    private final URI uri;
    private final EventLoopGroup eventLoopGroup;
    
    private Channel channel;
    private CompletableFuture<List<IceServerInfo>> connectFuture;

    private final Map<Long, Consumer<String>> handlers = new ConcurrentHashMap<>();

    public NetherNetXboxSignaling(String localNetworkId, String xboxToken) {
        this.localNetworkId = localNetworkId;
        this.xboxToken = xboxToken;
        this.uri = URI.create("wss://signal.franchise.minecraft-services.net/ws/v1.0/signaling/" + localNetworkId);
        this.eventLoopGroup = new NioEventLoopGroup(1);
    }

    public NetherNetXboxSignaling(long localNetworkId, String xboxToken) {
        this(Long.toUnsignedString(localNetworkId), xboxToken);
    }

    public NetherNetXboxSignaling(String xboxToken) {
        this(Long.toUnsignedString(ThreadLocalRandom.current().nextLong()), xboxToken);
    }

    @Override
    public String getLocalNetworkId() {
        return this.localNetworkId;
    }

    @Override
    public synchronized CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
        // If already connecting or connected, return the existing future
        if (connectFuture != null) {
            return connectFuture;
        }

        connectFuture = new CompletableFuture<>();
        
        try {
            SslContext sslCtx = SslContextBuilder.forClient().build();
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, false, 
                new DefaultHttpHeaders().add("Authorization", xboxToken)
            );

            Bootstrap b = new Bootstrap();
            b.group(eventLoopGroup)
             .channel(NioSocketChannel.class)
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();
                     p.addLast(sslCtx.newHandler(ch.alloc(), uri.getHost(), 443));
                     p.addLast(new HttpClientCodec(), new HttpObjectAggregator(8192));
                     p.addLast("ws-handshake", new WebSocketClientProtocolHandler(handshaker));
                     p.addLast("handler", NetherNetXboxSignaling.this);
                 }
             });

            this.channel = b.connect(uri.getHost(), 443).sync().channel();
        } catch (Exception e) {
            connectFuture.completeExceptionally(e);
        }
        return connectFuture;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            log.info("NetherNet Signaling WebSocket Connected");
            startPingLoop(ctx);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    private void startPingLoop(ChannelHandlerContext ctx) {
        ctx.executor().scheduleAtFixedRate(() -> {
            JsonObject ping = new JsonObject();
            ping.addProperty("Type", 0); // RequestType::Ping
            ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(ping)));
        }, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        if (channel != null && channel.isActive()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("Type", 1);
            msg.addProperty("To", targetNetworkId);
            msg.addProperty("Message", data);
            channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(msg)));
        }
    }

    @Override
    public void setSignalHandler(long connectionId, Consumer<String> handler) {
        this.handlers.put(connectionId, handler);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        try {
            JsonObject json = gson.fromJson(text, JsonObject.class);
            if (!json.has("Type")) return;

            int type = json.get("Type").getAsInt();
            switch (type) {
                case 2: // Credentials
                    if (json.has("Message") && !connectFuture.isDone()) {
                        connectFuture.complete(parseTurnServers(json.get("Message").getAsString()));
                    }
                    break;
                case 1: // Signal
                    if (json.has("Message")) {
                        String rawMsg = json.get("Message").getAsString();
                        dispatchSignal(rawMsg);
                    }
                    break;
            }
        } catch (Exception e) {
            log.error("Signaling error", e);
        }
    }

    private void dispatchSignal(String rawMsg) {
        // Format: TYPE CONNECTION_ID PAYLOAD
        try {
            String[] parts = rawMsg.split(" ", 3);
            if (parts.length >= 2) {
                long connectionId = Long.parseUnsignedLong(parts[1]);
                Consumer<String> handler = handlers.get(connectionId);
                if (handler != null) {
                    handler.accept(rawMsg);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to dispatch signal: {}", rawMsg);
        }
    }
    
    private List<IceServerInfo> parseTurnServers(String jsonString) {
        List<IceServerInfo> result = new ArrayList<>();
        try {
            JsonObject root = gson.fromJson(jsonString, JsonObject.class);
            if (root.has("TurnAuthServers")) {
                JsonArray servers = root.getAsJsonArray("TurnAuthServers");
                for (JsonElement el : servers) {
                    JsonObject server = el.getAsJsonObject();
                    if (server.has("Urls")) {
                        List<String> urls = new ArrayList<>();
                        server.getAsJsonArray("Urls").forEach(u -> urls.add(u.getAsString()));
                        
                        IceServerInfo info = new IceServerInfo();
                        info.urls = urls;
                        if (server.has("Username")) info.username = server.get("Username").getAsString();
                        if (server.has("Password")) info.password = server.get("Password").getAsString();
                        result.add(info);
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    @Override
    public void close() {
        if (channel != null) channel.close();
        eventLoopGroup.shutdownGracefully();
    }
}