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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class NetherNetXboxSignaling extends SimpleChannelInboundHandler<TextWebSocketFrame> implements NetherNetClientSignaling, NetherNetServerSignaling {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetXboxSignaling.class);
    private static final Gson gson = new Gson();

    private final String xboxToken;
    private final String localNetworkId;
    private final URI uri;
    private final EventLoopGroup eventLoopGroup;
    
    private Channel channel;
    private CompletableFuture<List<IceServerInfo>> connectFuture;

    private final Map<Long, Consumer<String>> handlers = new ConcurrentHashMap<>();
    private NetherNetServerSignaling.NewConnectionHandler newConnectionHandler;

    private volatile List<IceServerInfo> iceServers = new ArrayList<>();

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
        this(Long.toUnsignedString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)), xboxToken);
    }

    @Override
    public String getLocalNetworkId() {
        return this.localNetworkId;
    }

    @Override
    public synchronized CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
        // SocketAddress is ignored for Xbox Signaling Service connection
        return connectInternal();
    }

    @Override
    public void bind(SocketAddress localAddress) {
        // SocketAddress is ignored, we connect to the WS URL derived from NetworkID
        connectInternal();
    }

    private synchronized CompletableFuture<List<IceServerInfo>> connectInternal() {
        if (connectFuture != null) {
            return connectFuture;
        }

        connectFuture = new CompletableFuture<>();
        connectFuture.thenAccept(servers -> this.iceServers = servers);
        
        try {
            SslContext sslCtx = SslContextBuilder.forClient().build();
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, false, 
                new DefaultHttpHeaders()
                    .add("Authorization", xboxToken)
                    .add("Session-Id", UUID.randomUUID().toString())
                    .add("Request-Id", UUID.randomUUID().toString())
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
            log.error("Failed to connect to signaling service", e);
            connectFuture.completeExceptionally(e);
        }
        return connectFuture;
    }

    public List<IceServerInfo> getIceServers() {
        return this.iceServers;
    }

    @Override
    public void setNewConnectionHandler(NetherNetServerSignaling.NewConnectionHandler handler) {
        this.newConnectionHandler = handler;
    }

    @Override
    public void setAdvertisementData(PongData pongData) {
        // No-op for Xbox Signaling. 
        // Advertisement is handled via the Session Directory service (PUT /session/...).
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            log.debug("NetherNet Signaling WebSocket Connected");
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
        } else {
            log.debug("Attempted to send signal to {} but WebSocket is closed or null!", targetNetworkId);
        }
    }

    @Override
    public void setSignalHandler(long connectionId, Consumer<String> handler) {
        this.handlers.put(connectionId, handler);
    }

    @Override
    public void removeSignalHandler(long connectionId) {
        this.handlers.remove(connectionId);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        try {
            JsonObject json = gson.fromJson(text, JsonObject.class);
            if (!json.has("Type")) return;

            int type = json.get("Type").getAsInt();
            switch (type) {
                case 2 -> { // Credentials
                    if (json.has("Message") && !connectFuture.isDone()) {
                        connectFuture.complete(parseTurnServers(json.get("Message").getAsString()));
                    }
                }
                case 1 -> { // Signal
                    String sender = "0";
                    if (json.has("From")) {
                        sender = json.get("From").getAsString();
                    }

                    if (json.has("Message")) {
                        String rawMsg = json.get("Message").getAsString();
                        dispatchSignal(sender, rawMsg);
                    }   
                }
            }
        } catch (Exception e) {
            log.error("Signaling error processing frame: " + text, e);
        }
    }

    private void dispatchSignal(String sender, String rawMsg) {
        // Format: TYPE CONNECTION_ID PAYLOAD
        try {
            String[] parts = rawMsg.split(" ", 3);
            if (parts.length >= 2) {
                long connectionId = Long.parseUnsignedLong(parts[1]);
                Consumer<String> handler = handlers.get(connectionId);
                if (handler != null) {
                    handler.accept(rawMsg);
                } else if ("CONNECTREQUEST".equals(parts[0]) && newConnectionHandler != null) {
                    String payload = parts.length > 2 ? parts[2] : "";
                    newConnectionHandler.onConnect(connectionId, sender, payload);
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
            
            JsonArray servers = null;
            if (root.has("TurnAuthServers")) {
                servers = root.getAsJsonArray("TurnAuthServers");
            } else if (root.has("turnAuthServers")) {
                servers = root.getAsJsonArray("turnAuthServers");
            }

            if (servers != null) {
                for (JsonElement el : servers) {
                    JsonObject server = el.getAsJsonObject();
                    List<String> urls = new ArrayList<>();
                    
                    JsonArray urlsArray = null;
                    if (server.has("Urls")) {
                        urlsArray = server.getAsJsonArray("Urls");
                    } else if (server.has("urls")) {
                        urlsArray = server.getAsJsonArray("urls");
                    }

                    if (urlsArray != null) {
                        urlsArray.forEach(u -> urls.add(u.getAsString()));
                        
                        IceServerInfo info = new IceServerInfo();
                        info.urls = urls;
                        
                        if (server.has("Username")) info.username = server.get("Username").getAsString();
                        else if (server.has("username")) info.username = server.get("username").getAsString();
                        
                        if (server.has("Password")) info.password = server.get("Password").getAsString();
                        else if (server.has("password")) info.password = server.get("password").getAsString();
                        else if (server.has("Credential")) info.password = server.get("Credential").getAsString();
                        else if (server.has("credential")) info.password = server.get("credential").getAsString();

                        result.add(info);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse TURN servers", e);
        }
        
        log.debug("Successfully parsed " + result.size() + " ICE servers.");
        return result;
    }

    @Override
    public void close() {
        if (channel != null) channel.close();
        eventLoopGroup.shutdownGracefully();
    }
}