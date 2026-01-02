package dev.kastle.netty.channel.nethernet;

import dev.kastle.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;

import java.util.function.Supplier;

public class NetherNetChannelFactory<T extends Channel> implements ChannelFactory<T> {

    private final Supplier<T> channelCreator;

    private NetherNetChannelFactory(Supplier<T> channelCreator) {
        this.channelCreator = channelCreator;
    }

    @Override
    public T newChannel() {
        return channelCreator.get();
    }

    public static ChannelFactory<NetherNetServerChannel> server(PeerConnectionFactory factory, NetherNetServerSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetServerChannel(factory, signaling));
    }

    public static ChannelFactory<NetherNetClientChannel> client(PeerConnectionFactory factory, NetherNetClientSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetClientChannel(factory, signaling));
    }
}