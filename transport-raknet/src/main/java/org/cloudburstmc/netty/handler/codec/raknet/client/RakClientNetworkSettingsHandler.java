package org.cloudburstmc.netty.handler.codec.raknet.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Promise;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_GAME_PACKET;

public class RakClientNetworkSettingsHandler extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "rak-client-network-settings-handler";

    private final RakChannel channel;
    private final Promise<RakMessage> networkSettingsPacketPromise;

    public RakClientNetworkSettingsHandler(RakChannel channel, Promise<RakMessage> networkSettingsPacketPromise) {
        this.channel = channel;
        this.networkSettingsPacketPromise = networkSettingsPacketPromise;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ctx.channel().pipeline().remove(RakClientNetworkSettingsHandler.NAME);

        if (!(msg instanceof RakMessage)) {
            throw new IllegalStateException("First packet was not a RequestNetworkSettings packet: Not a RakMessage");
        }

        RakMessage packet = (RakMessage) msg;
        ByteBuf content = packet.content();

        if (content.capacity() < 4) {
            throw new IllegalStateException("First packet was not a RequestNetworkSettings packet: Content too small");
        }

        if (content.getByte(0) != (byte) ID_GAME_PACKET) {
            throw new IllegalStateException("First packet was not a RequestNetworkSettings packet: Invalid RakNet packet ID");
        }

        int rakVersion = this.channel.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION);

        switch (rakVersion) {
            case 11:
            case 10:
            case 9:
                if (content.getByte(1) != (byte) 0x06) break;
                if (content.getByte(2) != (byte) 0xc1) break;
                if ((content.getByte(3) & (byte) 0b10000111) != (byte) 0b00000001) break;
                this.networkSettingsPacketPromise.setSuccess(packet);
                return;
            case 8:
                if (content.getByte(1) != (byte) 0x07) break;
                if (content.getByte(2) != (byte) 0xc1) break;
                this.networkSettingsPacketPromise.setSuccess(packet);
                return;
            case 7:
                if (content.getByte(1) != (byte) 0x05) break;
                if (content.getByte(2) != (byte) 0xc1) break;
                this.networkSettingsPacketPromise.setSuccess(packet);
                return;
            default:
                throw new UnsupportedOperationException("Unsupported protocol version: " + rakVersion);
        }

        throw new IllegalStateException("First packet was not a RequestNetworkSettings packet: Invalid Bedrock packet ID");
    }
}
