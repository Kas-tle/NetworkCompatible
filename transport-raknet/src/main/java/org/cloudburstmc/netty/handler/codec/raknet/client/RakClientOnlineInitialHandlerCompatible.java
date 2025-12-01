package org.cloudburstmc.netty.handler.codec.raknet.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Promise;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.util.RakUtils;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_CONNECTED_PING;
import static org.cloudburstmc.netty.channel.raknet.RakConstants.IPV4_MESSAGE_SIZE;

public class RakClientOnlineInitialHandlerCompatible extends RakClientOnlineInitialHandler {
    public static final String NAME = "rak-client-online-initial-handler";

    private final Promise<RakMessage> networkSettingsPacketPromise;
    private long pingTime = 0;

    public RakClientOnlineInitialHandlerCompatible(RakChannel rakChannel, ChannelPromise successPromise, Promise<RakMessage> networkSettingsPacketPromise) {
        super(rakChannel, successPromise);
        this.networkSettingsPacketPromise = networkSettingsPacketPromise;
    }

    @Override
    void onSuccess(ChannelHandlerContext ctx) {
        super.onSuccess(ctx);

        // Wait for the RequestNetworkSettings packet before sending the final batch
        this.networkSettingsPacketPromise.addListener(future -> {
            ByteBuf incomingBuffer = ctx.alloc().ioBuffer();
            this.writeIncomingConnection(ctx, incomingBuffer, pingTime);
            ctx.write(new RakMessage(incomingBuffer, RakReliability.RELIABLE_ORDERED, RakPriority.NORMAL));

            ByteBuf pingBuffer = ctx.alloc().ioBuffer();
            pingBuffer.writeByte(ID_CONNECTED_PING);
            pingBuffer.writeLong(System.currentTimeMillis());
            ctx.write(new RakMessage(pingBuffer, RakReliability.UNRELIABLE, RakPriority.NORMAL));

            ctx.write(future.get());

            ctx.flush();
        });
    }

    @Override
    void onConnectionRequestAccepted(ChannelHandlerContext ctx, ByteBuf buf) {
        buf.skipBytes(1);
        RakUtils.skipAddress(buf);
        buf.skipBytes(2); // System index

        while (buf.isReadable(IPV4_MESSAGE_SIZE + 16)) {
            RakUtils.skipAddress(buf);
        }
        this.pingTime = buf.readLong();
        return;
    }
}
