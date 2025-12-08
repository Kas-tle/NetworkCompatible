package dev.kastle.netty.channel.nethernet;

import dev.onvoid.webrtc.PeerConnectionFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class NetherNetChannelFactory<T extends Channel> implements ChannelFactory<T> {
    private final PeerConnectionFactory peerConnectionFactory;
    private final Constructor<T> constructor;

    public NetherNetChannelFactory(Class<T> channelClass, PeerConnectionFactory factory) {
        this.peerConnectionFactory = factory;
        try {
            this.constructor = channelClass.getConstructor(PeerConnectionFactory.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Channel class " + channelClass.getName() + " must have a public constructor accepting PeerConnectionFactory", e);
        }
    }

    @Override
    public T newChannel() {
        try {
            return constructor.newInstance(peerConnectionFactory);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Failed to create channel", e);
        }
    }

    public static ChannelFactory<NetherNetServerChannel> server(PeerConnectionFactory factory) {
        return new NetherNetChannelFactory<>(NetherNetServerChannel.class, factory);
    }

    public static ChannelFactory<NetherNetClientChannel> client(PeerConnectionFactory factory) {
        return new NetherNetChannelFactory<>(NetherNetClientChannel.class, factory);
    }
}