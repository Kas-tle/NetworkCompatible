package dev.kastle.netty.handler.codec.nethernet;

import dev.kastle.netty.channel.nethernet.NetherNetConstants;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NetherNetDiscovery extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetDiscovery.class);

    private final long networkId;
    private final Map<Long, Consumer<String>> signalHandlers = new ConcurrentHashMap<>();
    private Channel channel;
    private byte[] pongData;
    private BiConsumer<Long, String> newConnectionHandler;
    private BiConsumer<Long, ByteBuf> discoveryCallback;

    public NetherNetDiscovery(long networkId) {
        this.networkId = networkId;
    }

    public void bind() {
        bind(NetherNetConstants.DISCOVERY_PORT);
    }

    public void bind(int port) {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
             .channel(NioDatagramChannel.class)
             .option(ChannelOption.SO_BROADCAST, true)
             .handler(this);

            this.channel = bootstrap.bind(port).sync().channel();
            log.info("NetherNet Discovery listening on port {}", port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void bind(InetSocketAddress address) {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
             .channel(NioDatagramChannel.class)
             .option(ChannelOption.SO_BROADCAST, true)
             .handler(this);

            this.channel = bootstrap.bind(address).sync().channel();
            log.info("NetherNet Discovery listening on {}", address);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void sendDiscoveryRequest(InetSocketAddress target, BiConsumer<Long, ByteBuf> onServerFound) {
        this.discoveryCallback = onServerFound;
        
        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_REQUEST);
        buf.writeLongLE(this.networkId);
        buf.writeZero(8); // Padding
        
        sendPacket(buf, target);
    }

    public void setPongData(String serverName, String levelName, int gameType, int playerCount, int maxPlayerCount) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(4); // Version
        writeString(buf, serverName);
        writeString(buf, levelName);
        buf.writeByte(gameType << 1); // Encoded as value << 1
        buf.writeIntLE(playerCount);
        buf.writeIntLE(maxPlayerCount);
        buf.writeBoolean(false); // isEditorWorld
        buf.writeBoolean(false); // Hardcore
        buf.writeZero(2); // Unknown

        byte[] binaryData = new byte[buf.readableBytes()];
        buf.readBytes(binaryData);
        buf.release();
        
        String hex = ByteBufUtil.hexDump(binaryData);
        byte[] hexBytes = hex.getBytes(StandardCharsets.UTF_8);
        
        ByteBuf response = Unpooled.buffer();
        response.writeIntLE(hexBytes.length);
        response.writeBytes(hexBytes);
        
        this.pongData = new byte[response.readableBytes()];
        response.readBytes(this.pongData);
        response.release();
    }

    public void registerSignalHandler(long connectionId, Consumer<String> handler) {
        this.signalHandlers.put(connectionId, handler);
    }
    
    public void unregisterSignalHandler(long connectionId) {
        this.signalHandlers.remove(connectionId);
    }

    public void setNewConnectionHandler(BiConsumer<Long, String> handler) {
        this.newConnectionHandler = handler;
    }

    /**
     * Sends a signal immediately and schedules it to be resent periodically 
     * until the returned ScheduledFuture is cancelled.
     */
    public ScheduledFuture<?> sendSignalRetrying(InetSocketAddress recipient, long targetNetworkId, String data, long delayMs) {
        return channel.eventLoop().scheduleAtFixedRate(() -> {
            log.debug("Resending signal to {}: {}", recipient, data);
            sendSignal(recipient, targetNetworkId, data);
        }, 0, delayMs, TimeUnit.MILLISECONDS);
    }

    public void sendSignal(InetSocketAddress recipient, long targetNetworkId, String data) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_MESSAGE);
        buf.writeLongLE(this.networkId); // Sender ID
        buf.writeZero(8); // Padding

        buf.writeLongLE(targetNetworkId); // Recipient ID
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
        buf.writeIntLE(dataBytes.length);
        buf.writeBytes(dataBytes);

        sendPacket(buf, recipient);
    }

    private void sendPacket(ByteBuf packetData, InetSocketAddress target) {
        try {
            byte[] encrypted = NetherNetConstants.encryptDiscoveryPacket(packetData);
            channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(encrypted), target));
        } catch (Exception e) {
            log.error("Failed to encrypt discovery packet", e);
        } finally {
            packetData.release();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf content = packet.content();
        ByteBuf decrypted = null;
        try {
            decrypted = NetherNetConstants.decryptDiscoveryPacket(content);
        } catch (Exception e) {
            log.debug("Failed to decrypt discovery packet from {}", packet.sender(), e);
            return;
        }
        
        if (decrypted == null) {
            log.debug("Received invalid discovery packet from {}", packet.sender());
            return;
        }

        try {
            int packetId = decrypted.readUnsignedShortLE();
            long senderId = decrypted.readLongLE();
            decrypted.skipBytes(8); // Padding

            if (senderId == this.networkId) {
                log.debug("Ignoring own discovery packet");
                return;
            }

            switch (packetId) {
                case NetherNetConstants.ID_DISCOVERY_REQUEST:
                    log.trace("Handled discovery request from {}", packet.sender());
                    handleRequest(senderId, packet.sender());
                    break;
                case NetherNetConstants.ID_DISCOVERY_MESSAGE:
                    log.trace("Handled discovery message from {}", packet.sender());
                    log.trace("Message Data: {}", decrypted.toString(StandardCharsets.UTF_8));
                    handleMessage(decrypted, senderId);
                    break;
                case NetherNetConstants.ID_DISCOVERY_RESPONSE:
                    log.trace("Handled discovery response from {}", packet.sender());
                    if (discoveryCallback != null) {
                        log.trace("Response Data: {}", decrypted.toString(StandardCharsets.UTF_8));
                        // Pass the payload (decrypted buffer) to the callback
                        // We retain it because we are passing it out of the pipeline handler
                        discoveryCallback.accept(senderId, decrypted.retain());
                    }
                    break;
            }
        } catch (Exception e) {
            log.debug("Error processing discovery packet from {}", packet.sender(), e);
        } finally {
            decrypted.release();
        }
    }

    private void handleRequest(long senderId, InetSocketAddress sender) {
        if (this.pongData == null) return;

        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_RESPONSE);
        buf.writeLongLE(this.networkId);
        buf.writeZero(8);
        buf.writeBytes(this.pongData);

        sendPacket(buf, sender);
    }

    private void handleMessage(ByteBuf data, long senderId) {
        long recipientId = data.readLongLE();
        if (recipientId != this.networkId) return;

        int len = data.readIntLE();
        String messageData = data.readCharSequence(len, StandardCharsets.UTF_8).toString();

        String[] parts = messageData.split(" ", 3);
        if (parts.length < 2) return;

        try {
            String type = parts[0];
            long connectionId = Long.parseUnsignedLong(parts[1]);
            
            Consumer<String> handler = signalHandlers.get(connectionId);
            if (handler != null) {
                handler.accept(messageData);
            } else if (NetherNetConstants.SIGNAL_CONNECT_REQUEST.equals(type) && newConnectionHandler != null) {
                String payload = parts.length > 2 ? parts[2] : "";
                newConnectionHandler.accept(connectionId, payload);
            }
        } catch (NumberFormatException e) {
            // Invalid format
        }
    }

    public void close() {
        if (channel != null) {
            channel.close();
        }
    }

    public boolean isActive() {
        return channel != null && channel.isActive();
    }
    
    private void writeString(ByteBuf buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        this.writeUnsignedVarInt(buf, b.length); 
        buf.writeBytes(b);
    }

    private void writeUnsignedVarInt(ByteBuf buf, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buf.writeByte((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.writeByte((byte) value);
    }
}