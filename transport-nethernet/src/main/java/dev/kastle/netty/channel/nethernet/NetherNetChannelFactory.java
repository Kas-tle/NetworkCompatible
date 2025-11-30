package dev.kastle.netty.channel.nethernet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.socket.DatagramChannel;

import java.lang.reflect.InvocationTargetException;

public class NetherNetChannelFactory<T extends Channel> implements ChannelFactory<T> {
    private final Class<T> channelClass;

    public NetherNetChannelFactory(Class<T> channelClass) {
        this.channelClass = channelClass;
    }

    @Override
    public T newChannel() {
        try {
            return channelClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException("Failed to create channel", e);
        }
    }

    public static ChannelFactory<NetherNetServerChannel> server(Class<? extends DatagramChannel> clazz) {
        return new NetherNetChannelFactory<>(NetherNetServerChannel.class);
    }

    public static ChannelFactory<NetherNetClientChannel> client(Class<? extends DatagramChannel> clazz) {
        return new NetherNetChannelFactory<>(NetherNetClientChannel.class);
    }
}