package org.cloudburstmc.netty.handler.codec.raknet.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_GAME_PACKET;

public class RakClientNetworkSettingsHandler extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "rak-client-network-settings-handler";
    public static final AttributeKey<ByteBuf> NETWORK_SETTINGS_PAYLOAD = AttributeKey.valueOf("network-settings-payload");

    private final RakClientChannel channel;

    public RakClientNetworkSettingsHandler(RakClientChannel channel) {
        this.channel = channel;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.write(msg, promise);
            return;
        }

        ByteBuf packet = (ByteBuf) msg;

        if (packet.readableBytes() < 4) {
            ctx.write(msg, promise);
            return;
        }

        if (packet.getByte(0) != ID_GAME_PACKET) {
            ctx.write(msg, promise);
            return;
        }

        int rakVersion = this.channel.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION);

        switch (rakVersion) {
            case 11:
            case 10:
            case 9:
                if (packet.getByte(1) != 0x06) break;
                if (packet.getByte(2) != 0xc1) break;
                if ((packet.getByte(3) & 0b10000111) != 0b00000001) break;
                onNetworkSettings(ctx, packet);
                return;
            case 8:
                if (packet.getByte(1) != 0x07) break;
                if (packet.getByte(2) != 0xc1) break;
                onNetworkSettings(ctx, packet);
                return;
            case 7:
                if (packet.getByte(1) != 0x05) break;
                if (packet.getByte(2) != 0xc1) break;
                onNetworkSettings(ctx, packet);
                return;
            default:
                throw new UnsupportedOperationException("Unsupported protocol version: " + rakVersion);
        }

        ctx.write(msg, promise);
    }

    private void onNetworkSettings(ChannelHandlerContext ctx, ByteBuf packet) {
        ctx.channel().attr(NETWORK_SETTINGS_PAYLOAD).set(packet.retain());
        ctx.channel().pipeline().remove(RakClientNetworkSettingsHandler.NAME);
    }
}