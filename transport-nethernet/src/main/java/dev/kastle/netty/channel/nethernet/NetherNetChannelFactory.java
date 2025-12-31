package dev.kastle.netty.channel.nethernet;

import dev.kastle.netty.channel.nethernet.signaling.NetherNetSignaling;
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

    public static ChannelFactory<NetherNetServerChannel> server(PeerConnectionFactory factory) {
        return new NetherNetChannelFactory<>(() -> new NetherNetServerChannel(factory));
    }

    public static ChannelFactory<NetherNetClientChannel> client(PeerConnectionFactory factory, NetherNetSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetClientChannel(factory, signaling));
    }
}